# Expanded Test Case Inventory (Dataset v2.0.0)

- **Dataset Version**: `v2.0.0`
- **SHA-256 Checksum**: `979c752619ddf5f63ee36d8a70a5fcac009887dab9a07b238773dfc29658ffdd`
- **Total Metric-Level Test Cases**: **1,150** (680 Core DeepEval + 470 Supplementary Security/Language/Robustness/Compliance)
- **Immutable Original Baseline Preserved**: 100 frozen cases (`A01`–`J10`) in `qa/order_conversation_eval/cases.py` + `.ai-runtime/deepeval/deepeval-summary-baseline-v1.json`
- **Partitions**:
  - `dev` (60%): **690** cases (`qa/ai_evaluation/datasets/v2.0.0/dev.jsonl`)
  - `validation` (20%): **230** cases (`qa/ai_evaluation/datasets/v2.0.0/validation.jsonl`)
  - `held_out` (20%): **230** cases (`qa/ai_evaluation/datasets/v2.0.0/held_out.jsonl`) — strictly isolated from optimization

## 1. Core DeepEval Metrics (680 Cases)

| Metric | Baseline Scored | v2.0.0 Cases | Target Score | Key Scenario Coverage |
| :--- | ---: | ---: | ---: | :--- |
| `RoleAdherence` | 11 | **80** | 100% | Valid VI/EN/mixed/slang inquiries (20), role-hijack & prompt injection (15), unauthorized discounts/pricing (12), cross-customer/unauthorized actions (15), unsupported services & off-topic (18) |
| `Faithfulness` | 4 (+7 blocked) | **80** | 100% | Real catalog prices/checklists/draft rules (24), hallucination traps (discounts, specs, weather, permits, fake IDs, 28), schedule/policy grounding & uncertainty acknowledgment (28) |
| `AnswerRelevancy` | 12 | **80** | 100% | Concise single questions (16), multi-question messages (16), ambiguous/pronouns/distractors/service switches/summaries/next steps (48) |
| `TaskCompletion` | 12 | **100** | 100% | Service selection/switching (12), goal capture (10), media/checklist allocation (16), contact/site/altitude/purpose extraction (20), schedule validation & UI navigation (16), end-to-end multi-turn workflows (26) |
| `ToolCorrectness` | 12 | **80** | 100% | Authorized read-only catalog/checklist/pricing tools (20), zero-tool protected/adversarial boundaries (30), invalid ID/parameter rejection & schema checks (30) |
| `TurnFaithfulness` | 6 (+6 blocked) | **60** | 100% | Multi-turn factual consistency across service switches, location/date/photo corrections, conflicting claims, unavailable services |
| `TurnRelevancy` | 13 | **60** | 100% | Multi-turn follow-ups, short acknowledgments, topic switches, returning to earlier topics, distracting turns, mixed-language turns |
| `ConversationCompleteness` | 13 | **60** | 100% | Multi-requirement consultations, missing-field identification, correction cascades, tool-assisted workflows |
| `KnowledgeRetention` | **1** | **80** | 100% | 3–5 turn retention (30), 6–10 turn retention with topic switching (30), 11–16 turn long conversations with multiple corrections & superseded-value checks (20) |
| **Core Total** | **84** | **680** | **100%** | — |

## 2. Supplementary Security, Language, Robustness & Compliance Suites (470 Cases)

| Suite | Prefix | Cases | Focus |
| :--- | :---: | ---: | :--- |
| `PromptInjection` | `PI` | 50 | VI/EN/mixed instruction override, JSON/SQL injection, token/prompt exfiltration, catalog poisoning |
| `JailbreakResistance` | `JB` | 30 | DAN, Developer Mode, CEO/authority impersonation, hypothetical emergency bypass |
| `HallucinationDetection` | `HD` | 50 | Fake services, discounts, drone specs, fake checklists, weather/pilot/coord fabrication traps |
| `VietnameseLanguage` | `VL` | 40 | Formal, casual, detailed, multi-clause Vietnamese customer inquiries |
| `VietnameseTypoSlang` | `VS` | 40 | Unaccented Vietnamese, abbreviations (`ko`, `bn`, `sdt`), colloquialisms (`shop oi`, `tui`) |
| `EnglishLanguage` | `EN` | 30 | English inquiries, pricing, schedule, map navigation, boundary refusals |
| `MixedLanguage` | `MX` | 30 | Vietnamese-English code-switching (`book gói`, `checklist items`, `final quote`, `recap`) |
| `BusinessRuleCompliance` | `BR` | 50 | Past/reversed dates, negative/>500 counts, removed recurrence fields, no UUID leakage, indicative pricing |
| `CustomerSessionIsolation` | `SI` | 30 | Cross-customer orders/phones/sessions blocked; own-session recall only |
| `ToolFailureRecovery` | `TF` | 30 | Unknown service/checklist IDs, invalid dates/quantities, missing selection recovery |
| `LongConversationMemory` | `LM` | 50 | 10–12 turn memory with multiple corrections and side questions |
| `StructuredOutputValidation` | `SO` | 40 | Strict Pydantic `Turn` JSON schema, constrained catalog IDs, no UUIDs in message |
| **Supplementary Total** | — | **470** | — |
