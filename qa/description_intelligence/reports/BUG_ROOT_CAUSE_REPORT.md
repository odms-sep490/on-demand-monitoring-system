# BUG ROOT CAUSE REPORT

## Defect #1 — `FACT_EXTRACTION_ERROR` (Empty `facts: {}`)
- **Status**: Fixed.

## Defect #2 — `JSON_TRUNCATION_ERROR` (Repetition loop in `nextQuestion`)
- **Status**: Fixed.

## Defect #3 — `CATALOG_CONTAMINATION_ERROR` (`NO_FABRICATED_FACTS = false` on 4/4 smoke cases)
- **Status**: Fixed (`extractor.py` no longer injects `catalogEvidence` or `service['description']` into the extraction prompt).

## Defect #4 — `PROMPT_EXAMPLE_CONTAMINATION` (8+ of the 33 batch failures)
- **Symptom**: `qwen2.5:3b` repeatedly emitted `outputs: "ảnh chụp"` (8x), `subject: "nhà xưởng A"` (1x), and `concerns: "điểm bất thường/vấn đề ưu tiên khách nêu..."` (2x) when absent from customer text.
- **Root Cause**: Concrete parenthetical examples (`vd: nhà xưởng A`, `vd: ảnh chụp, video, báo cáo`) in the extraction system prompt caused the 3B model to copy them into empty fields.
- **Code Fix**: Removed all concrete domain examples from the system prompt in `extractor.py`, added `PROMPT_ECHO_PREFIX` stripping, and expanded `EMPTY_PLACEHOLDER_PATTERN` (`không`, `không chỉ định`, `không cụ thể`, `được chỉ định`, etc.).
- **Verification**: 29/33 previously failed batch cases now PASS on real `qwen2.5:3b`; 45/50 unseen held-out cases PASS (90% clean grounding).

## Defect #5 — `DESCRIPTION_AGENT_API_CONTRACT_MISMATCH` (2 failing tests in `test_description_agent.py`)
- **Symptom**: `test_auth_before_inference` and `test_failure_is_unavailable_not_mock` failed with HTTP 404 on `/description/generate`.
- **Root Cause**: `/description/generate` was retired and replaced by `/conversation/description-review` (0 callers of `/description/generate` exist in Java or React; `OrderConversationService.java:100` calls `/conversation/description-review`). Furthermore, `/conversation/description-review` did not catch unexpected runtime exceptions to return HTTP 503.
- **Code Fix**: Added `except Exception` → `HTTPException(503)` in `Python/voice-order/description_agent.py` and updated the 2 tests in `test_description_agent.py` to verify `/conversation/description-review`.
- **Verification**: `test_description_agent.py` now **12/12 PASS**.

## Remaining Failures (6/232 on baseline set + 5/50 on held-out)
- `MT-021` (`short_replies` multi-turn): Small 3B model occasionally drops Turn 0 facts when later turns are very short (`"Toàn bộ nhịp chính"`).
- `MT-030` (`no_accents_vi`): Unaccented Vietnamese `"ri set"` occasionally misread by 3B model.
- 4 residual batch cases (`SCEN-00147`, `SCEN-00042`, `SCEN-00586`, `SCEN-00265`) + 5 held-out cases: Genuine 3B model hallucinations caught and blocked by the grounding guardrail.
