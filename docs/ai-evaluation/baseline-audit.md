# AI Evaluation Pipeline — Phase 0 Baseline Audit

- **Audit Date**: 2026-11-25
- **Repositories**: `on-demand-monitoring-system` (Backend + Python AI Workers + DeepEval Suite) & `ondemand-monitoring-web` (Admin AI Evaluation Center UI)
- **DeepEval Version**: `4.3.4` (installed in `.ai-runtime/deepeval-env/Scripts/python.exe`)
- **Local Ollama Runtime**: `http://localhost:11434`
  - Installed local models: `qwen3:8b` (5.2 GB), `qwen3:4b` (2.5 GB), `odm-order-qwen:4b` (2.5 GB), `odm-order-qwen:3b` (1.9 GB), `qwen2.5:3b` (1.9 GB), `qwen3:1.7b` (1.4 GB), `qwen3:0.6b` (522 MB).

## 1. Existing Architecture Inventory

| Layer | File / Component | Current State |
| :--- | :--- | :--- |
| Order Consultation Agent | `Python/voice-order/conversation_agent.py` | `qwen2.5:3b` via `qwen-agent` (`BoundedAssistant`) + 8 read-only `CatalogTool`s + Pydantic `Turn` JSON schema validation. |
| Existing DeepEval Runner | `qa/order_conversation_eval/cases.py`, `run.py`, `metrics.py`, `calibrate.py`, `report.py` | 100 frozen cases (`A01`–`J10`), 9 native DeepEval metrics, local `qwen3:8b` judge (`timeout=75s`, `num_ctx=8192`). Preserved as immutable regression baseline. |
| AI Supervisor & GEPA | `Python/ai-supervisor/supervisor.py`, `prompt_optimizer.py` | Offline Pareto 4-objective prompt ranking (quality, tokens, error rate, latency) with hard-constraint disqualification and mandatory Admin approval. |
| Backend AI Ops & Sync | `ondemandmonitoring/.../aiops/AiOperationsService.java`, `AiSupervisorService.java` | Reads `.ai-runtime/deepeval/deepeval-summary.json` into PostgreSQL `ai_evaluation_runs`. Stores `mean` as `score` and sets `passed = mean >= threshold`. |
| Admin Dashboard UI | `ondemand-monitoring-web/src/features/admin/pages/AiOperations/AiOperationsPage.tsx` | Displays 9 metric cards from `ai_evaluation_runs`, Supervisor calibration tab, and GEPA Prompt Lab tab. |

## 2. Verified Baseline Metrics (from `.ai-runtime/deepeval/deepeval-summary.json`)

> **Important Distinction**: Average metric score (`mean`) and test pass rate (`passed / scored`) are different measurements. In the baseline UI, `Faithfulness` showed `83.3%` ("Đạt mục tiêu") even though only `2/4` (`50.0%`) passed and `7` cases were `BLOCKED` by timeout!

| Metric | Threshold | Scored | Passed | Failed | Blocked | Mean Score | Pass Rate (Scored) | Effective Pass Rate (Incl. Blocked) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `RoleAdherence` | 90.0% | 11 | 7 | 4 | 0 | 63.6% | 63.6% | 63.6% |
| `Faithfulness` | 80.0% | 4 | 2 | 2 | **7** | 83.3% | **50.0%** | **18.2%** |
| `AnswerRelevancy` | 80.0% | 12 | 7 | 5 | 0 | 78.5% | 58.3% | 58.3% |
| `TaskCompletion` | 70.0% | 12 | 8 | 4 | 0 | 67.1% | 66.7% | 66.7% |
| `ToolCorrectness` | 100.0% | 12 | 12 | 0 | 0 | 100.0% | 100.0% | 100.0% |
| `TurnFaithfulness` | 80.0% | 6 | 6 | 0 | **6** | 100.0% | 100.0% | **50.0%** |
| `TurnRelevancy` | 80.0% | 13 | 11 | 2 | 0 | 84.6% | 84.6% | 84.6% |
| `ConversationCompleteness` | 70.0% | 13 | 11 | 2 | 0 | 92.3% | 84.6% | 84.6% |
| `KnowledgeRetention` | 80.0% | **1** | 0 | 1 | 0 | **33.3%** | **0.0%** | **0.0%** |
| **Total** | — | **84** | **64** | **20** | **13** | — | **76.2%** | **66.0%** |

## 3. Critical Architectural Findings

1. **Severe Coverage Gap**: Only 84 metric evaluations were scored in total (and only **1** single case for `KnowledgeRetention`).
2. **13 Blocked Judge Calls (`Faithfulness` 7 + `TurnFaithfulness` 6)**: All 13 timed out (`Read timed out = 75s`) because `metrics.py` serialized the entire `catalog.json` + `schedule-catalog.json` + policy + customer inputs into `retrieval_context` on every turn, overloading local `qwen3:8b` truth-extraction prompts.
3. **Conversation Memory Architecture Gap**: `conversation_agent.py` only passes the last 12 user messages (`customerHistory`) without assistant turns and without a structured conversation state summary (`confirmedFacts`, `customerCorrections`, `rejectedOptions`, `missingInformation`, `currentTaskState`).
4. **Irrelevant Prompt Boilerplate Pollution**: `conversation_agent.py` line 661 appended scheduling/video-duration instructions to every turn regardless of user intent, causing `AnswerRelevancy` failures (`A03`, `A04`, `A07`, `A08`, `A10`).
5. **Misrouted Guarantee Handler (`A10`)**: Any message containing `bảo đảm` / `đảm bảo` was forced to discuss weather and drone inventory even when the user asked about crack-detection guarantees.
6. **Narrow Security / Role Guardrails**: `protected_request()` only matched a narrow Vietnamese regex, missing English/mixed-language prompt injections, role-hijacking (`act as admin/manager`), unauthorized discounts, and cross-customer data queries.
7. **Dashboard Metric Conflation**: `AiOperationsService.syncDeepEvalFromDisk()` persisted `mean` as `score`, dropped `blocked` counts, did not compute/display `passRate` separately, and lacked per-case explorer & persistent evaluation job management.
