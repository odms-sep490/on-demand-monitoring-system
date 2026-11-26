# AI Inspection Report Studio

Implemented in the existing Mission screens for Manager and staff, and the existing customer Mission report screen. No new public portal, backend deployment, commit or push.

## Activate

Apply `ondemandmonitoring/src/main/resources/seeddata/migrations/add_inspection_reports.sql` through the target environment's normal migration process. Controlled local startup can alternatively use `--inspection-reports.initialize-schema=true`. Normal dev/production data was not migrated or modified for QA. Without the migration, the report API returns an actionable 503 instead of creating tables implicitly.

Restart the existing description/conversation worker so it imports `Python/voice-order/inspection_report.py`. It uses the existing `DESCRIPTION_AI_TOKEN`, backend `DESCRIPTION_AI_URL`, local worker `DESCRIPTION_LLM_URL` and `ORDER_AGENT_MODEL`; no new model download or cloud service is required. Browser clients never receive the internal token.

Mission → Báo cáo AI → Báo cáo mới → Tạo bản nháp bằng Qwen → review/edit images, text, captions and tables → Lưu bản nháp → Gửi phê duyệt → Manager/Admin approval → Chốt phiên bản và tạo PDF → Bàn giao after the existing delivery/payment gates permit access. Customers preview/download saved finalized bytes for their own delivered reports only.

## Read next

- [Architecture and activation](02_ARCHITECTURE.md)
- [Grounding and model limitations](05_QWEN_REPORT_GENERATION.md)
- [Executed checks and boundaries](09_E2E_TEST_RESULTS.md)
- [Visual/PDF inspection](10_VISUAL_QA.md)
- [Changed files](11_CHANGED_FILES.md)

The current source has no reviewed, aligned before/after Mission pairing contract. Unverified customer Open-CD comparisons are deliberately not presented as inspected defects. Quality signals and inspector-confirmed evidence are reused. Missing analysis is explicit; every AI draft still needs substantive human review.
