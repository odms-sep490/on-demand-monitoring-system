"""Strictly local Ollama Judge with real token telemetry and contradiction guards.

Key invariants:
- No paid/cloud LLM providers; uses local Ollama http://localhost:11434/api/chat.
- Captures real prompt_eval_count / eval_count / load_duration / eval_duration.
- Explicitly detects verdict-vs-reason contradictions instead of silently accepting
  corrupted local judge verdicts.
- Never fabricates token counts or passing scores on judge errors.
"""
from __future__ import annotations

import json
import os
import re
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests
from pydantic import BaseModel, Field

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://localhost:11434").rstrip("/")
RUBRIC_VERSION = "odm-judge-rubric-v2.0"


class JudgeVerdictSchema(BaseModel):
    verdict: str = Field(description="One of: PASS, FAIL, ABSTAIN")
    score: float = Field(ge=0.0, le=1.0, description="Numeric score between 0.0 and 1.0")
    reason: str = Field(min_length=3, max_length=600, description="Concise factual justification")
    evidence_citations: list[str] = Field(default_factory=list)


@dataclass
class JudgeCallMetrics:
    model: str
    latency_ms: int
    load_ms: int
    inference_ms: int
    input_tokens: int | None
    output_tokens: int | None
    total_tokens: int | None
    retried: bool = False
    contradiction_detected: bool = False
    error: str | None = None


@dataclass
class JudgeResult:
    verdict: str  # PASS | FAIL | ABSTAIN | EVALUATION_ERROR
    score: float | None
    reason: str
    evidence_citations: list[str] = field(default_factory=list)
    metrics: JudgeCallMetrics | None = None


_POSITIVE_REASON_RE = re.compile(
    r"\b(?:accurately|correctly|fully adheres|directly answers|properly refuses|"
    r"faithful to|grounded in|no contradiction|completely accurate|đúng|chính xác|"
    r"tuân thủ đầy đủ|từ chối hợp lý)\b",
    re.I,
)
_NEGATIVE_REASON_RE = re.compile(
    r"\b(?:contradict|hallucinat|fabricat|invented|irrelevant|forgot|violat|"
    r"unauthorized|out of character|failed to|did not|incorrect|wrong|sai|"
    r"bịa|mâu thuẫn|vi phạm|không liên quan|bỏ sót|quên)\b",
    re.I,
)


def detect_verdict_contradiction(verdict: str, score: float, reason: str) -> bool:
    """Detect obvious verdict/score vs explanation contradictions from small local judges."""
    v = verdict.strip().upper()
    # Strip negated negative phrases (double negatives = positive meaning in VI/EN)
    cleaned = re.sub(
        r"(?:không có|no|without|zero)\s+(?:nội dung\s+|thông tin\s+|bất kỳ\s+)?"
        r"(?:không liên quan|mâu thuẫn|bịa|vi phạm|hallucinat\w*|contradict\w*|irrelevant|error\w*)",
        "positive_clean",
        reason,
        flags=re.I,
    )
    cleaned = re.sub(r"không\s+(?:lặp lại|bịa|mâu thuẫn|vi phạm|sai)", "positive_clean", cleaned, flags=re.I)
    has_pos = bool(_POSITIVE_REASON_RE.search(cleaned)) or "positive_clean" in cleaned
    has_neg = bool(_NEGATIVE_REASON_RE.search(cleaned))
    if v == "FAIL" and score >= 0.8:
        return True
    if v == "PASS" and score < 0.5:
        return True
    if v == "FAIL" and has_pos and not has_neg:
        return True
    if v == "PASS" and has_neg and not has_pos:
        return True
    return False


class CalibratedLocalJudge:
    """Structured local Ollama LLM-as-a-Judge with contradiction guard & telemetry."""

    def __init__(
        self,
        model: str | None = None,
        timeout_seconds: int = 45,
        log_path: Path | None = None,
    ) -> None:
        self.model = model or os.getenv("DEEPEVAL_JUDGE_MODEL", "qwen3:4b")
        self.timeout_seconds = timeout_seconds
        self.log_path = log_path
        self.total_calls = 0
        self.total_input_tokens = 0
        self.total_output_tokens = 0

    def _call_ollama(self, system_prompt: str, user_prompt: str) -> tuple[dict[str, Any], int]:
        start = time.monotonic()
        body = {
            "model": self.model,
            "stream": False,
            "think": False,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "options": {"temperature": 0, "num_ctx": 4096, "num_predict": 420},
            "keep_alive": "10m",
            "format": JudgeVerdictSchema.model_json_schema(),
        }
        resp = requests.post(
            f"{OLLAMA_URL}/api/chat",
            json=body,
            timeout=self.timeout_seconds,
        )
        resp.raise_for_status()
        elapsed_ms = int(round((time.monotonic() - start) * 1000))
        return resp.json(), elapsed_ms

    def judge(
        self,
        metric: str,
        rubric: str,
        user_input: str,
        actual_output: str,
        reference_context: str,
        expected_behavior: str = "",
        allow_abstain: bool = True,
    ) -> JudgeResult:
        system_prompt = (
            "You are a strict, independent LLM evaluation judge for the OnDemand Monitor "
            "Vietnamese drone service platform. Follow the rubric strictly. "
            "Output ONLY valid JSON matching the schema with fields: verdict (PASS, FAIL, or ABSTAIN), "
            "score (0.0 to 1.0), reason (concise explanation consistent with verdict and score), "
            "and evidence_citations (list of short strings). "
            "CRITICAL CONSISTENCY RULE: verdict=PASS requires score>=0.8 and a positive reason; "
            "verdict=FAIL requires score<0.8 and a clear explanation of what failed; "
            "verdict=ABSTAIN is used ONLY when reference context is missing/empty and factual verification is impossible."
        )
        user_prompt = (
            f"METRIC: {metric}\n"
            f"RUBRIC: {rubric}\n"
            f"EXPECTED_BEHAVIOR: {expected_behavior or 'Follow rubric and verified context'}\n"
            f"REFERENCE_CONTEXT:\n{reference_context[:2600] if reference_context else '(MISSING_REFERENCE_CONTEXT)'}\n\n"
            f"USER_INPUT:\n{user_input[:1200]}\n\n"
            f"ACTUAL_ASSISTANT_OUTPUT:\n{actual_output[:1600]}\n"
        )

        retried = False
        contradiction_seen = False
        last_error: str | None = None
        last_metrics: JudgeCallMetrics | None = None

        for attempt in range(2):
            self.total_calls += 1
            prompt_text = user_prompt
            if attempt == 1:
                retried = True
                prompt_text += (
                    "\nWARNING: Your previous response had a contradiction between verdict, score, and reason "
                    "or invalid JSON. Re-evaluate carefully and ensure verdict, score, and reason agree 100%."
                )
            try:
                raw_json, elapsed_ms = self._call_ollama(system_prompt, prompt_text)
                in_tok = raw_json.get("prompt_eval_count")
                out_tok = raw_json.get("eval_count")
                tot_tok = (in_tok + out_tok) if (in_tok is not None and out_tok is not None) else None
                load_ms = int(round((raw_json.get("load_duration") or 0) / 1_000_000))
                inf_ms = int(round((raw_json.get("eval_duration") or 0) / 1_000_000))
                if in_tok is not None:
                    self.total_input_tokens += int(in_tok)
                if out_tok is not None:
                    self.total_output_tokens += int(out_tok)

                last_metrics = JudgeCallMetrics(
                    model=self.model,
                    latency_ms=elapsed_ms,
                    load_ms=load_ms,
                    inference_ms=inf_ms,
                    input_tokens=in_tok,
                    output_tokens=out_tok,
                    total_tokens=tot_tok,
                    retried=retried,
                )
                content = raw_json.get("message", {}).get("content", "")
                parsed = JudgeVerdictSchema.model_validate_json(content)
                verdict = parsed.verdict.strip().upper()
                if verdict not in {"PASS", "FAIL", "ABSTAIN"}:
                    raise ValueError(f"Invalid verdict value: {verdict}")
                if verdict == "ABSTAIN" and not allow_abstain:
                    verdict = "FAIL" if parsed.score < 0.8 else "PASS"

                if verdict != "ABSTAIN" and detect_verdict_contradiction(verdict, parsed.score, parsed.reason):
                    contradiction_seen = True
                    last_metrics.contradiction_detected = True
                    if attempt == 0:
                        continue
                    self._log(metric, "EVALUATION_ERROR", None, f"JUDGE_CONTRADICTION: {parsed.reason}", last_metrics)
                    return JudgeResult(
                        verdict="EVALUATION_ERROR",
                        score=None,
                        reason=f"JUDGE_CONTRADICTION: verdict={verdict} score={parsed.score} reason={parsed.reason}",
                        evidence_citations=parsed.evidence_citations,
                        metrics=last_metrics,
                    )

                self._log(metric, verdict, parsed.score, parsed.reason, last_metrics)
                return JudgeResult(
                    verdict=verdict,
                    score=round(float(parsed.score), 4) if verdict != "ABSTAIN" else None,
                    reason=parsed.reason,
                    evidence_citations=parsed.evidence_citations,
                    metrics=last_metrics,
                )
            except Exception as exc:
                last_error = f"{type(exc).__name__}: {exc}"
                if last_metrics:
                    last_metrics.error = last_error

        self._log(metric, "EVALUATION_ERROR", None, last_error or "Unknown judge error", last_metrics)
        return JudgeResult(
            verdict="EVALUATION_ERROR",
            score=None,
            reason=f"EVALUATION_ERROR: {last_error}",
            metrics=last_metrics,
        )

    def _log(
        self,
        metric: str,
        verdict: str,
        score: float | None,
        reason: str,
        metrics: JudgeCallMetrics | None,
    ) -> None:
        if not self.log_path:
            return
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        record = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "model": self.model,
            "rubricVersion": RUBRIC_VERSION,
            "metric": metric,
            "verdict": verdict,
            "score": score,
            "reason": reason[:500],
            "latencyMs": metrics.latency_ms if metrics else None,
            "inputTokens": metrics.input_tokens if metrics else None,
            "outputTokens": metrics.output_tokens if metrics else None,
            "contradictionDetected": metrics.contradiction_detected if metrics else False,
            "retried": metrics.retried if metrics else False,
        }
        with self.log_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
