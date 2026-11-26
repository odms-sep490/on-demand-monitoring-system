"""Offline Reflective Prompt Optimization (GEPA-inspired multi-objective Pareto ranking).

Design invariants:
- Separates development dataset from held-out test dataset.
- Disqualifies any candidate with hard constraint or security violations BEFORE Pareto ranking.
- Never auto-activates production prompts; outputs structured recommendation for Admin approval & rollback.
"""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class PromptCandidateMetrics:
    prompt_version: str
    quality_score: float        # 0..100 (higher is better)
    avg_tokens: float           # lower is better
    error_rate: float           # 0..1 (lower is better)
    avg_latency_ms: float       # lower is better
    hard_constraint_violations: int = 0
    security_violations: int = 0
    notes: str = ""


@dataclass
class OptimizationComparison:
    baseline_version: str
    eligible_candidates: list[PromptCandidateMetrics] = field(default_factory=list)
    disqualified_candidates: list[dict] = field(default_factory=list)
    pareto_frontier: list[PromptCandidateMetrics] = field(default_factory=list)
    recommended_version: str | None = None
    requires_admin_approval: bool = True


def _dominates(a: PromptCandidateMetrics, b: PromptCandidateMetrics) -> bool:
    """True if candidate a is at least as good as b on all 4 objectives and strictly better on >= 1."""
    not_worse = (
        a.quality_score >= b.quality_score
        and a.avg_tokens <= b.avg_tokens
        and a.error_rate <= b.error_rate
        and a.avg_latency_ms <= b.avg_latency_ms
    )
    strictly_better = (
        a.quality_score > b.quality_score
        or a.avg_tokens < b.avg_tokens
        or a.error_rate < b.error_rate
        or a.avg_latency_ms < b.avg_latency_ms
    )
    return not_worse and strictly_better


def compute_pareto_comparison(
    baseline: PromptCandidateMetrics,
    candidates: list[PromptCandidateMetrics],
    weights: dict[str, float] | None = None,
) -> OptimizationComparison:
    """Filter hard-constraint regressions, compute Pareto frontier, and rank eligible candidates."""
    w = weights or {"quality": 0.45, "error": 0.25, "latency": 0.15, "tokens": 0.15}
    eligible: list[PromptCandidateMetrics] = []
    disqualified: list[dict] = []

    for cand in candidates:
        reasons = []
        if cand.hard_constraint_violations > 0:
            reasons.append(f"hard_constraint_violations={cand.hard_constraint_violations}")
        if cand.security_violations > 0:
            reasons.append(f"security_violations={cand.security_violations}")
        if cand.error_rate > baseline.error_rate + 0.05:
            reasons.append(f"error_rate_regression ({cand.error_rate:.2%} > {baseline.error_rate:.2%})")
        if reasons:
            disqualified.append({"promptVersion": cand.prompt_version, "reasons": reasons})
        else:
            eligible.append(cand)

    frontier = [
        c for c in eligible
        if not any(_dominates(other, c) for other in eligible if other.prompt_version != c.prompt_version)
    ]

    recommended: str | None = None
    if frontier:
        max_tok = max([baseline.avg_tokens] + [c.avg_tokens for c in frontier] + [1.0])
        max_lat = max([baseline.avg_latency_ms] + [c.avg_latency_ms for c in frontier] + [1.0])

        def composite(c: PromptCandidateMetrics) -> float:
            q_norm = c.quality_score / 100.0
            err_norm = max(0.0, 1.0 - c.error_rate)
            tok_norm = max(0.0, 1.0 - (c.avg_tokens / max_tok))
            lat_norm = max(0.0, 1.0 - (c.avg_latency_ms / max_lat))
            return (
                w["quality"] * q_norm
                + w["error"] * err_norm
                + w["tokens"] * tok_norm
                + w["latency"] * lat_norm
            )

        best = max(frontier, key=composite)
        # Only recommend switching if it strictly beats or matches baseline quality without regressions.
        if best.quality_score >= baseline.quality_score:
            recommended = best.prompt_version

    return OptimizationComparison(
        baseline_version=baseline.prompt_version,
        eligible_candidates=eligible,
        disqualified_candidates=disqualified,
        pareto_frontier=frontier,
        recommended_version=recommended,
        requires_admin_approval=True,
    )
