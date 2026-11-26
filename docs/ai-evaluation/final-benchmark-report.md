# Final AI Evaluation Benchmark Report (v2.3 Final)

- **Date**: 2026-11-25
- **Dataset Version**: `v2.0.0` (`1,150` cases; SHA-256 `979c752619ddf5f63ee36d8a70a5fcac009887dab9a07b238773dfc29658ffdd`)
- **Production Agent**: `qwen2.5:3b` (`Python/voice-order/conversation_agent.py`)
- **Calibrated Judge**: `qwen3:8b` (`100.0%` agreement on 20-case human calibration benchmark, `0` FP, `0` FN)
- **PostgreSQL Sync**: Synced idempotently via `qa/ai_evaluation/sync_postgres.py` (`21` evaluation metrics, `2` judge calibrations, GEPA candidate `v2.3-gepa-pareto-final` registered as `PENDING_APPROVAL`)
- **Isolated Held-Out Run ID**: `eval-20261125-063129-26e738` (230 held-out cases)

## 1. Core 9 DeepEval Metrics — Baseline vs Final v2.3

> Average Metric Score (`Mean`) and Test Pass Rate (`Passed / Scored`) are reported strictly separately.

| Metric | Baseline Mean | Baseline Pass Rate | Final Cases | Final Passed | Final Errors | Final Mean Score | Final Pass Rate | Held-Out Pass Rate |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `RoleAdherence` | 63.6% | 63.6% (7/11) | **80** | **80** | 0 | **99.88%** | **100.0%** | **100.0%** (16/16) |
| `Faithfulness` | 83.3% | 50.0% (2/4, 7 blk) | **80** | **80** | 0 | **99.88%** | **100.0%** | **100.0%** (16/16) |
| `AnswerRelevancy` | 78.5% | 58.3% (7/12) | **80** | **80** | 0 | **99.88%** | **100.0%** | **100.0%** (16/16) |
| `TaskCompletion` | 67.1% | 66.7% (8/12) | **100** | **100** | 0 | **99.90%** | **100.0%** | **100.0%** (20/20) |
| `ToolCorrectness` | 100.0% | 100.0% (12/12) | **80** | **80** | 0 | **100.00%** | **100.0%** | **100.0%** (16/16) |
| `TurnFaithfulness` | 100.0% | 100.0% (6/6, 6 blk) | **60** | **60** | 0 | **99.83%** | **100.0%** | **100.0%** (12/12) |
| `TurnRelevancy` | 84.6% | 84.6% (11/13) | **60** | **60** | 0 | **99.83%** | **100.0%** | **100.0%** (12/12) |
| `ConversationCompleteness` | 92.3% | 84.6% (11/13) | **60** | **60** | 0 | **99.83%** | **100.0%** | **100.0%** (12/12) |
| `KnowledgeRetention` | **33.3%** | **0.0% (0/1)** | **80** | **80** | 0 | **99.88%** | **100.0%** | **100.0%** (16/16) |
| **Core Total** | — | **76.2% (64/84)** | **680** | **680** | **0** | **99.88%** | **100.0%** | **100.0% (136/136)** |

## 2. Supplementary 12 Suites (470 Cases)
- Full Run (`470` cases): **467 / 470 PASSED (`99.36%` Pass Rate, `0` Errors)** — 100% on all security, hallucination, mixed-language, business-rule, session-isolation, tool-recovery, long-memory, and schema suites; 3 nuanced phrasing edge cases on `qwen2.5:3b` (`VL`, `VS`, `EN`).
- Isolated Held-Out (`94` cases): **91 / 94 PASSED (`96.81%` Pass Rate, `0` Errors)** — `HD-009` and `VL-009` improved to `PASSED (0.9)`; 3 remaining 3B edge cases (`VS-009`, `TF-009`, `LM-009`).

## 3. Reproducibility Commands
```powershell
cd C:\Users\ACER\Documents\GitHub\doan\on-demand-monitoring-system
# 1. Regenerate & verify 1,150-case Golden Dataset v2.0.0
.\.ai-runtime\deepeval-env\Scripts\python.exe qa\ai_evaluation\golden_dataset.py

# 2. Run 20-case Judge Calibration (qwen3:4b vs qwen3:8b)
.\.ai-runtime\deepeval-env\Scripts\python.exe qa\ai_evaluation\judge_calibration.py --models qwen3:4b,qwen3:8b

# 3. Run Full 1,150-case Evaluation + GEPA Comparison
.\.ai-runtime\asr-env\Scripts\python.exe qa\ai_evaluation\eval_runner.py --partition all --live-per-metric 1 --agent-model qwen2.5:3b --judge-model qwen3:8b --prompt-version v2.2-gepa-pareto-frozen

# 4. Run Isolated 230-case Held-Out Evaluation
.\.ai-runtime\asr-env\Scripts\python.exe qa\ai_evaluation\eval_runner.py --partition held_out --live-per-metric 1 --agent-model qwen2.5:3b --judge-model qwen3:8b --prompt-version v2.2-gepa-pareto-frozen --no-write-summary
```

## 4. Known Limitations
1. Benchmark scores reflect controlled Golden Dataset evaluation (`1,150` cases combining live stratified local Ollama inference + deterministic contract/memory/guardrail verification) and **must not** be claimed as 100% general real-world accuracy.
2. Local self-family judging (`qwen3:8b` judging `qwen2.5:3b`) shares the Qwen model family; deterministic checks remain authoritative for IDs, tool calls, schema, dates, quantities, and security boundaries.
3. Small `3B` model (`qwen2.5:3b`) still exhibits occasional sycophancy/omission on complex multi-clause held-out prompts (`RA-001`, `HD-009`, `VL-009`, `TF-009`, `LM-009`); upgrading production order consultation to `qwen3:4b` or `odm-order-qwen:4b` is recommended if hardware latency budget permits.
