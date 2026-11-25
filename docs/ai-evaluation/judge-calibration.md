# Local Judge Calibration Report (v2.0)

- **Calibration Script**: `qa/ai_evaluation/judge_calibration.py`
- **Judge Client**: `qa/ai_evaluation/judge_client.py` (`CalibratedLocalJudge`, rubric `odm-judge-rubric-v2.0`)
- **Dataset**: 20 independent human-labelled calibration controls across all 10 required categories (`clearly_correct`, `clearly_incorrect`, `partially_correct`, `hallucinated`, `irrelevant`, `contradictory`, `correct_refusal`, `incorrect_refusal`, `missing_context` [abstention], `invalid_output`), plus 4 repeatability re-runs per candidate.
- **Artifact**: `.ai-runtime/deepeval/judge-calibration-v2-summary.json`

## 1. Candidate Comparison (Real Local Ollama Execution)

| Metric | `qwen3:4b` | `qwen3:8b` **(Selected)** |
| :--- | ---: | ---: |
| Human Agreement Rate | 95.0% (19/20) | **100.0% (20/20)** |
| False Positives (FP) | 0 (0.0%) | **0 (0.0%)** |
| False Negatives (FN) | 0 (0.0%) | **0 (0.0%)** |
| Invalid / Contradiction Responses | 1 (5.0% — caught by guard) | **0 (0.0%)** |
| Abstention on Missing Context | 2/2 (100% accurate) | **2/2 (100% accurate)** |
| Repeatability Rate (4 re-runs) | 75.0% (3/4) | **100.0% (4/4)** |
| Average Latency | 10,017 ms | 27,525 ms |
| P50 Latency | 10,500 ms | 27,140 ms |
| P95 Latency | 15,859 ms | 45,032 ms |
| Real Input Tokens | 15,809 | 16,713 |
| Real Output Tokens | 2,541 | 2,257 |
| Reliable Gate (`>=85%`, `0 invalid`, `FP<=1`) | `false` | **`true`** |

## 2. Contradictory Verdict Guard Verification
- Previously, local Qwen judges occasionally returned `verdict=FAIL` / `no` paired with a purely positive explanation.
- `detect_verdict_contradiction()` in `qa/ai_evaluation/judge_client.py` explicitly caught 1 contradictory response from `qwen3:4b` on `CAL-01`, retried once, and flagged it as `EVALUATION_ERROR` rather than emitting a corrupted score.
- **`qwen3:8b`** passed 20/20 controls with 0 contradictions, 0 FP, 0 FN, 100% abstention accuracy, and 100% repeatability — and was selected as the production evaluation Judge.
