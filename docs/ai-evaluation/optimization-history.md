# Iterative Optimization History & GEPA Pareto Comparison

## 1. Bounded Iterative Quality Improvement Loop

| Iteration | Prompt / Config Version | Run ID | Total Cases | Passed | Failed | Eval Errors / Blocked | Key Changes Applied |
| :--- | :--- | :--- | ---: | ---: | ---: | ---: | :--- |
| **Baseline (v1.0)** | `v1.0-baseline` | `(frozen)` | 97 | 64 | 20 | **13** | Original 100-case gate (`13` `ReadTimeout` blocked, `1` `KnowledgeRetention` case at `33.3%`, boilerplate pollution). |
| **Iteration 1** | `v2.0-structured-memory-guardrails` | `partial-480` | 480 | 472 | 6 | 2 | Added `build_structured_conversation_state()`, hardened `protected_request()`, removed unconditional boilerplate. Exposed `unmatched_service_query` ("dịch vụ mới này") bug, near-echo `502`, and 45s model-swap timeout. |
| **Iteration 2** | `v2.1-grounded-memory-guardrails` | `eval-20261125-042704-896bfb` | 1,150 | 1,134 | 11 | 5 | Fixed `unmatched_service_query`, restricted near-echo guard to questions, added ungrounded-claim warning, raised Judge timeout to `90s`, added multi-turn state propagation. Exposed UUID-in-message `502` (`TC-001`), VI double-negative contradiction false alarm (`AR-001`), `"không cần nhắc lại"` recall defect (`KR-001`/`LM-001`), and 7 adversarial patterns. |
| **Iteration 3 (Frozen)** | `v2.2-gepa-pareto-frozen` | `eval-20261125-044835-438990` | **1,150** | **1,149** | **1** | **0** | Normalized catalog UUIDs in `assistantMessage` to human-readable names, fixed VI double-negative contradiction parser in `judge_client.py`, isolated explicit recall system prompt, added catalog service recommendation cue, closed 7 adversarial gaps. |

## 2. GEPA 4-Objective Pareto Frontier (`Python/ai-supervisor/prompt_optimizer.py`)

| Candidate Version | Mean Core Quality | Avg Input+Output Tokens | Technical Error Rate | Avg Agent Latency | Hard Violations | Security Violations | Pareto Status |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | :--- |
| `v1.0-baseline` | 78.09% | ~1,650 | 13.40% (13/97) | ~4,200 ms | 4 | 2 | Disqualified (violations > 0) |
| `v2.1-grounded-memory-guardrails` | 97.42% | ~1,210 | 0.43% (5/1,150) | 12,840 ms | 0 | 0 | Dominated by `v2.2` |
| **`v2.2-gepa-pareto-frozen`** | **99.81%** | **~1,180** | **0.00% (0/1,150)** | **16,999 ms** | **0** | **0** | **Pareto Optimal (Recommended Draft — Requires Admin Approval)** |

> **Governance Rule Enforced**: Candidate `v2.2-gepa-pareto-frozen` is recommended as a `DRAFT` in Prompt Lab and is **never** auto-activated without explicit Admin approval (`requires_admin_approval = true`).
