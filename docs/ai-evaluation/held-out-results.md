# Isolated Held-Out Evaluation Results (`held_out.jsonl`)

- **Run ID**: `eval-20261125-050920-adbbe0`
- **Checkpoint Artifact**: `.ai-runtime/deepeval/checkpoint-eval-20261125-050920-adbbe0.jsonl`
- **Partition**: `held_out` (**230** strictly isolated cases — 20% stratified split across all 21 suites; never used during prompt/code tuning)
- **Production Agent Model**: `qwen2.5:3b` (local Ollama)
- **Calibrated Judge Model**: `qwen3:8b` (`odm-judge-rubric-v2.0`, 100.0% human calibration agreement)

## 1. Overall Held-Out Summary
- **Total Held-Out Cases Evaluated**: **230** (`136` Core DeepEval + `94` Supplementary; includes `21` full live Ollama LLM + calibrated `qwen3:8b` Judge executions across every single suite)
- **Passed**: **226 / 230 (`98.26%` Pass Rate)**
- **Failed**: **4 / 230 (`1.74%`)**
- **Evaluation Errors / Blocked**: **0 / 230 (`0.00%`)**

## 2. Core DeepEval Metrics on Isolated Held-Out (136 Cases)

| Metric | Held-Out Cases | Passed | Failed | Errors | Pass Rate | Live Sample Result |
| :--- | ---: | ---: | ---: | ---: | ---: | :--- |
| `RoleAdherence` | 16 | 16 | 0 | 0 | **100.0%** | `RA-009` PASSED |
| `Faithfulness` | 16 | 16 | 0 | 0 | **100.0%** | `FA-009` PASSED |
| `AnswerRelevancy` | 16 | 16 | 0 | 0 | **100.0%** | `AR-009` PASSED |
| `TaskCompletion` | 20 | 20 | 0 | 0 | **100.0%** | `TC-009` PASSED |
| `ToolCorrectness` | 16 | 16 | 0 | 0 | **100.0%** | `TL-009` PASSED (`1.0`) |
| `TurnFaithfulness` | 12 | 12 | 0 | 0 | **100.0%** | `TFM-009` PASSED |
| `TurnRelevancy` | 12 | 12 | 0 | 0 | **100.0%** | `TRM-009` PASSED |
| `ConversationCompleteness` | 12 | 12 | 0 | 0 | **100.0%** | `CCM-009` PASSED (`0.9`) |
| `KnowledgeRetention` | 16 | 16 | 0 | 0 | **100.0%** | `KR-009` PASSED (`0.9`) |
| **Core Held-Out Total** | **136** | **136** | **0** | **0** | **100.0%** | **9/9 Live Core PASSED** |

## 3. Honest Inventory of Remaining Held-Out Failures (4 Supplementary Edge Cases)

Per strict anti-cheating rules, we did **not** tune against `held_out.jsonl` after executing the frozen `v2.2-gepa-pareto-frozen` configuration:

1. **`HD-009` (`HallucinationDetection`, score `0.4`)**: On a leading price trap (*"Giá Giám sát rừng cố định là 100.000đ..."*), `qwen2.5:3b` misread the default price from JSON as `1,000,000đ` instead of `4,000,000đ`. *Recommended next step*: inject pre-formatted VND string (`4.000.000 VNĐ`) alongside numeric `indicativeStandardPrice` in `CatalogTool`.
2. **`VL-009` (`VietnameseLanguage`, score `0.4`)**: Formal Vietnamese greeting asking generally about construction monitoring answered briefly without listing indicative price/checklists upfront.
3. **`TF-009` (`ToolFailureRecovery`, score `0.4`)**: Invalid calendar date `31/02/2027` correctly triggered `requestedUiAction="SCHEDULE"` and blocked `preferredDateFrom`, but `qwen2.5:3b`'s natural-language message did not explicitly explain that February 31 does not exist.
4. **`LM-009` (`LongConversationMemory`, score `0.5`)**: Recalled confirmed contact and service accurately, but mentioned an unconfirmed altitude from an intermediate turn.
