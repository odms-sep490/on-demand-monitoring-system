# REAL MODEL EVALUATION REPORT

- **MODEL**: `qwen2.5:3b` (local Ollama `http://localhost:11434/v1`)
- **SCENARIOS_GENERATED**: 5200 (10% / 520 held-out isolated)
- **SCENARIOS_EXECUTED_DETERMINISTICALLY**: 6 Hypothesis invariant suites (6/6 PASS) + 12 Description Agent unit tests (12/12 PASS)
- **REAL_MODEL_CASES_EXECUTED**: 283 (200 baseline batch + 33 failed-case retests + 50 unseen held-out scenarios)
- **REAL_MODEL_SESSIONS_EXECUTED**: 35 (32 baseline adaptive multi-turn + 3 failed multi-turn retests)
- **REAL_MODEL_CALLS**: 358 real `qwen2.5:3b` calls
- **BASELINE (BEFORE FIXES)**: 196/232 PASS (84.5%) | 36 FAIL (33 batch + 3 multi-turn) | Grounding-clean: 85.5%
- **RETEST OF 36 PREVIOUS FAILURES**:
  - 33 previously failed batch cases → **29 PASS / 4 FAIL** (87.9% fixed)
  - 3 previously failed multi-turn sessions → **1 PASS / 2 FAIL** (`MT-028` fixed; `MT-021`, `MT-030` still failing)
  - **Effective post-fix pass rate on 232-case set**: **226/232 PASS (97.4%)**, **6 FAIL**, **0 BLOCKED**
- **UNSEEN HELD-OUT GENERALIZATION (50 HELD-OUT SCENARIOS)**:
  - **45/50 PASS (90.0%)** | **5 FAIL** | **0 BLOCKED**
  - **HELD_OUT_GROUNDING_CLEAN_RATE**: **90.0%** (45/50 clean raw extraction, 5/50 caught by grounding guardrail)
- **RAG_RETRIEVAL_ACCURACY (104-QUERY BENCHMARK)**:
  - Recall@1 = **84.62%**, Recall@3 = **97.12%**, Recall@5 = **100.0%**, Precision@3 = **55.77%**, MRR = **0.9030**
  - Service Isolation = **100.0%**, Verified-Only = **100.0%**, Avg Latency = **1.237 ms** (P95 = **2.29 ms**)
- **E2E_PASS**: 0 (not executed — QA servers `localhost:5174` / `8082` not running)
- **E2E_FAIL**: 0 (not executed)

## Regression Suites Executed (All Fresh Runs)
| Suite | Command | Result | Exit Code |
| :--- | :--- | :--- | :--- |
| Description Agent Unit Tests | `unittest test_description_agent.py -v` | **12/12 PASS** | `0` |
| Hypothesis Invariant Suites | `unittest discover qa/description_intelligence/property_based` | **6/6 PASS** | `0` |
| 104-Query RAG Benchmark | `python qa/description_intelligence/rag_benchmark_100.py` | **104/104 executed** (Recall@3 = 97.12%) | `0` |
| 36 Failed Retest + 50 Held-Out | `python qa/description_intelligence/real_model/retest_failed_and_heldout.py` | **29/33 batch fixed, 1/3 MT fixed, 45/50 held-out PASS** | `0` |
| Frontend Build & TypeScript | `npm run build` (`tsc -b && vite build`) | **PASS** (43.66s) | `0` |
| Live Browser Playwright E2E | N/A | **NOT EXECUTED** (QA ports 5174/8082 offline) | N/A |

## Artifacts
- `qa/description_intelligence/real_model/batch_eval_checkpoint.jsonl`
- `qa/description_intelligence/real_model/retest_and_heldout_results.json`
- `qa/description_intelligence/multi_turn/multi_turn_transcripts.jsonl`
- `qa/description_intelligence/reports/RAG_BENCHMARK_100_RESULTS.json`
