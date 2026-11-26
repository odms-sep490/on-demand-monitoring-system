# Architecture

Mission Detail -> ReportStudio -> authenticated backend report APIs -> persistent report/job row -> bounded scheduled worker -> ReportEvidenceService -> existing private storage/quality/evidence contracts -> existing Python/Qwen worker -> strict document/reference validation -> AI_DRAFT -> human review -> immutable approval -> PDFBox artifact -> existing Mission delivery gate -> authorized customer.

New backend package `com.ondemandmonitoring.inspectionreport`: ReportDocument, ReportEvidenceService, InspectionReportService, ReportGenerationJob, InspectionReportController, ReportPdf, ReportSchema, ReportSourceGuard, ReportRichText.
Frontend `src/features/inspection-report`: reportApi, ReportStudio, ReportTextEditor, scoped CSS. Entry points in manager and operator MissionDetailPage and customer CustomerMissionReportPage. No parallel portal.

## Migration / activation
Apply `ondemandmonitoring/src/main/resources/seeddata/migrations/add_inspection_reports.sql` to the target database using normal migration procedure. Alternatively enable `inspection-reports.initialize-schema=true` for a controlled startup. Automatic initialization is opt-in to avoid changing the normal development DB while compiling/testing. QA runs on a separate PostgreSQL cluster at localhost:5549, backend 8086, worker 8012, Vite 5175; no production notifications/payments.

## API
- GET/POST `/api/missions/{missionId}/inspection-reports`
- GET/PATCH `/api/inspection-reports/{id}`; edits require `{revision,document}`.
- POST `/{id}/generate`, `/submit-review`, `/request-revision`, `/approve`, `/finalize`, `/deliver`, `/cancel-generation` require `{revision}`.
- POST `/{id}/rewrite` accepts a fixed instruction enum; returns a proposal only.
- GET `/{id}/versions`, `/{id}/images/{mediaId}`, `/{id}/preview`, `/{id}/pdf`.
All resources are scope checked. Media/storage URLs and credentials are not returned. No arbitrary URL/path is accepted.

A dedicated single report worker isolates slow inference from delivery/retention schedules; the scheduled poller only dispatches and does not register or replace the application scheduler. Queue claiming uses database locks, a global queue bound and a ten-minute interrupted-job lease. Explicit revision numbers protect all mutations. API errors preserve their status under the generic application advice.
