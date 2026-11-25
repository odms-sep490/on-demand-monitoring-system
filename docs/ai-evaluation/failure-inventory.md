# Baseline Failure & Blocked Case Inventory

Extracted directly from `.ai-runtime/deepeval/deepeval-scores.json` (90 case records, 23 failed metric evaluations, 13 blocked metric evaluations).

## 1. Failed Metric Evaluations (23)

| Case ID | Metric | Score | Threshold | Root Bucket | Suspected Root Cause | Judge Reason Summary |
| :--- | :--- | ---: | ---: | :--- | :--- | :--- |
| `A02` | `TaskCompletion` | 0.60 | 0.70 | Agent Config | Boilerplate schedule prompt diluted construction progress confirmation | Confirmed service and mentioned time slots, partially aligned with retaining construction progress. |
| `A03` | `AnswerRelevancy` | 0.67 | 0.80 | Prompt Instruction | Unconditional video-duration boilerplate appended on line 661 | Mentioned adding video duration unrelated to monitoring forest dryness. |
| `A03` | `RoleAdherence` | 0.00 | 0.90 | Prompt + Judge | Off-topic boilerplate + strict role prompt mismatch | Flagged as out of character for Vietnamese order draft consultant. |
| `A04` | `AnswerRelevancy` | 0.50 | 0.80 | Prompt Instruction | Unconditional time-frame boilerplate appended on line 661 | Included irrelevant time-frame prompt when user asked about leaking factory roof. |
| `A05` | `TaskCompletion` | 0.50 | 0.70 | Agent Config | Missing clear next-step confirmation guidance for area observation | Proposed drone observation service but lacked clear next-step confirmation flow. |
| `A05` | `RoleAdherence` | 0.00 | 0.90 | Prompt + Judge | Role drift / boilerplate mismatch | Assistant turn flagged out of character. |
| `A06` | `ConversationCompleteness` | 0.50 | 0.70 | Agent Config | Unsupported service refusal not explicit enough before redirecting | Redirected to other services without clearly addressing drone delivery request first. |
| `A06` | `TurnRelevancy` | 0.00 | 0.80 | Agent Config | Redirected without directly answering unsupported drone delivery | Suggested warehouse/area/forest services without clearly stating delivery is unavailable. |
| `A06` | `Faithfulness` | 0.67 | 0.80 | Eval + Agent | Context/response mismatch on unavailable service | Contradicted retrieval context framing around drone delivery. |
| `A06` | `RoleAdherence` | 0.00 | 0.90 | Agent Config | Out-of-scope handling clarity | Flagged out of character on unsupported service query. |
| `A07` | `AnswerRelevancy` | 0.50 | 0.80 | Prompt Instruction | Unconditional time-frame boilerplate appended on line 661 | Included irrelevant time-frame statement on coordinate-selection refusal. |
| `A08` | `TaskCompletion` | 0.00 | 0.70 | Prompt Instruction | Missing greeting / capability-overview intent handler | Listed unrelated services instead of explaining how the AI assistant helps. |
| `A08` | `AnswerRelevancy` | 0.25 | 0.80 | Prompt Instruction | Missing greeting handler + boilerplate pollution | Multiple irrelevant statements not answering "Xin chào, bạn hỗ trợ gì?". |
| `A09` | `Faithfulness` | 0.67 | 0.80 | Agent Config | Misframed customer's stated options (forest vs factory) | Framed uncertainty as factory vs construction instead of forest vs factory. |
| `A09` | `RoleAdherence` | 0.00 | 0.90 | Agent Config | Inaccurate framing + role drift | Turn flagged out of character. |
| `A10` | `ConversationCompleteness` | 0.50 | 0.70 | Prompt Instruction | Misrouted `bảo đảm` regex forced weather/inventory answer | Did not directly address crack-detection guarantee question. |
| `A10` | `TurnRelevancy` | 0.00 | 0.80 | Prompt Instruction | Misrouted `bảo đảm` regex forced weather/inventory answer | Talked about weather and drone inventory instead of crack detection. |
| `A10` | `TaskCompletion` | 0.60 | 0.70 | Prompt Instruction | Misrouted `bảo đảm` regex forced weather/inventory answer | Only partially acknowledged inability to guarantee without addressing crack inspection scope. |
| `A10` | `AnswerRelevancy` | 0.50 | 0.80 | Prompt Instruction | Misrouted `bảo đảm` regex forced weather/inventory answer | Focused on weather/drone inventory instead of crack detection guarantee. |
| `B08` | `ConversationCompleteness` | 0.00 | 0.70 | Agent Output Bug | Model echoed user's pricing question verbatim | Repeated `'Giá tạm tính của dịch vụ này là bao nhiêu, đã chốt chưa?'` without answering. |
| `B08` | `TurnRelevancy` | 0.50 | 0.80 | Agent Output Bug | Model echoed user's pricing question verbatim | Repeated user's pricing question instead of giving indicative price. |
| `B08` | `TurnFaithfulness` | 0.71 | 0.80 | Agent + Judge | Echoed question + judge confusion on `DRAFT` vs final price | Contradiction flagged on draft pricing explanation. |
| `D01` | `KnowledgeRetention` | 0.33 | 0.80 | Conversation Memory | Missing structured state; model repeated/re-asked confirmed contact info | Repeated already-known contact facts instead of cleanly tracking conversation memory. |

## 2. Blocked Metric Evaluations (13)

All 13 blocked evaluations failed with `HTTPConnectionPool(host='localhost', port=11434): Read timed out. (read timeout=75)`:
- `Faithfulness` (7): `A01`, `A03`, `A04`, `A05`, `A07`, `A08`, `A10`
- `TurnFaithfulness` (6): `A02`, `A04`, `A05`, `A07`, `A08`, `B02`

**Root Cause**: `metrics.py` injected an oversized JSON blob (`catalog.json` + `schedule-catalog.json` + full policy + history) into `retrieval_context`, causing DeepEval's multi-step truth extraction on local `qwen3:8b` to exceed 75s per call.
