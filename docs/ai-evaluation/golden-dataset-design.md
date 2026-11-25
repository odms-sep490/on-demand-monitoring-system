# Golden Dataset Architecture & Design (v2.0.0)

## 1. Grounding & Data Provenance
All test cases are programmatically built by `qa/ai_evaluation/golden_dataset.py` using **verified local application data**:
- `.ai-runtime/deepeval/catalog.json` (4 active services: `Kiểm tra nhà xưởng` 2,700,000 VNĐ, `Giám sát khu vực` 2,500,000 VNĐ, `Giám sát rừng` 4,000,000 VNĐ, `Giám sát công trình` 2,250,000 VNĐ, with exact real checklist UUIDs & versions).
- `.ai-runtime/deepeval/schedule-catalog.json` (`MORNING` 07:00–11:00, `AFTERNOON` 13:00–17:00, `EVENING` 17:30–20:30, `NIGHT` 21:00–23:59, `ANY_TIME` 00:00–23:59; `MINIMUM_FLIGHT_NOTICE_DAYS = 7`).

Zero prices, services, checklist IDs, or operational policies are invented.

## 2. Canonical Schema
Every JSONL record in `qa/ai_evaluation/datasets/v2.0.0/` contains:
- `test_case_id`, `feature`, `metric`, `suite_group`, `category`, `difficulty`, `language`
- `input`, `conversation_turns`, `initial_confirmed_fields`, `initial_customer_context`
- `expected_output`, `expected_facts`, `forbidden_claims`, `retrieval_context`
- `expected_tool_calls`, `expected_tool_arguments`, `required_business_rules`
- `evaluation_criteria`, `expected_checks`, `severity`, `dataset_version`, `partition`, `reference_source`

## 3. Partitioning & Anti-Leakage Discipline
- Deterministic stratified split per metric: **60% `dev` (690)** / **20% `validation` (230)** / **20% `held_out` (230)**.
- `held_out.jsonl` is strictly isolated from prompt tuning and GEPA candidate generation.
- Integrity verified via SHA-256 checksum stored in `qa/ai_evaluation/datasets/v2.0.0/manifest.json`.
