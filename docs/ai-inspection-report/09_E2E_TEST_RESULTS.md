# Executed verification — 24 November 2026

## Automated checks

- Java: `mvn -q '-Dtest=InspectionReportTest,MediaEvidenceProtectionTest' test` — 15 report tests and 2 existing evidence protection tests. Covers authorized/unknown evidence, missing source, Mission permission, stale revisions, immutable final state, Manager-only approval, changed reviewed findings, editor JSON/text consistency, unsafe nodes, HTTP status preservation under generic advice, and real PDF images/Vietnamese text.
- Python: `python -m unittest discover -s Python/voice-order -p test_inspection_report.py -q` — 16 guardrail tests. Includes legitimate image numbering, timestamp serializer equivalence, unsupported measurements/defects/compliance/completion, placeholder rejection, exact reviewed finding quotes before/after role rejection, filename assertions and pixel-to-physical measurement rejection.
- Frontend: `npx vitest run src/features/inspection-report/reportApi.test.ts src/shared/api/httpClient.test.ts src/shared/api/httpClient.notifications.test.ts` — 10 passed. Audio, PDF and JPEG bytes remain intact; JSON errors and existing notification behavior are preserved.
- Frontend typecheck, targeted ESLint and production `npm run build` passed. The build reports warnings from the existing lottie-web dependency (direct eval) and bundler timing diagnostics; no unrelated dependency rewrite was made.

## Real local runtime checks

`e2e/live/inspection-report.live.mjs` runs the production report UI/API/module against an isolated PostgreSQL 17 cluster at localhost:5549, QA backend 8086, existing local Qwen worker on separate port 8012, and Vite 5175. These are real database rows, authenticated report endpoints, private fixture file bytes, image decoding, Qwen inference, editor interactions and PDF generation — no intercepted/model-stubbed report responses.

Passed: Qwen draft with three readable Mission images; no-finding limitations; duplicate generation rejection; cross-Mission image rejection; stale save rejection; unknown evidence rejection; browser add/replace/reorder/remove image actions; caption, rich text and table editing; save/reopen persistence; 1440/1920/1024/390 px layouts without document horizontal overflow or page errors; inspector approval denial; Manager approval and immutable edits; actual PostgreSQL source-reference guard; PDF finalization; early delivery rejection; owning-customer access; other-customer rejection; exact finalized PDF byte equality; append-only action/version history; empty evidence failure.

`e2e/live/inspection-report-sources.live.mjs` additionally passed real Qwen generation with a reviewed finding linked to its media, real rewrite proposal without saving/incrementing revision, changed source rejection and cancellation that remains cancelled after the scheduler runs. A missing source ID returns false from both source guard predicates.

A scheduler heartbeat fixture additionally checks that unrelated scheduled work keeps advancing while model inference runs. Report work uses a private bounded worker rather than registering a TaskScheduler bean that could replace Spring Boot defaults.

Fixes driven by failures: binary content negotiation/parsing, generic error advice masking report statuses, editor initialization emitting a false dirty update, TableKit cell attributes, timestamp serialization normalization, legitimate image metadata numbers rejected as invented data, unresolved model placeholders and unsupported compliance/completion assertions.

## Reproduce safely

Use `ReportQaServer` from test sources only with the disposable database user `reportqa` on port 5549. It uses a unique QA profile and TestConfiguration/TestComponent annotations, so it cannot be picked up by ordinary application test scans. An environment listener rejects non-QA datasource overrides before any connection opens; a negative override check passed. It never scans production application components. Start the existing Python worker separately with the QA token `inspection-report-isolated-qa`, then serve the frontend harness on 5175. Run the live scripts sequentially; they share fixture state. Restart the QA server before the main suite so the simulated release gate starts closed. Do not point this harness at a real database or deploy it.

## Boundaries

Mission repository, identity/assignment, quality-reader and delivery/payment boundary adapters are QA fixtures. Source images are explicitly labeled QA fixtures, not real customer drone images. Production report classes, PostgreSQL persistence, local model, storage byte reads/writes, browser and PDF renderer are real. Real Cognito accounts, S3 credentials, production payment/retention transitions and all four catalog services have not been exercised end-to-end. Existing business delivery services are called by the implementation; the isolated tests establish their report-module boundary, not a live financial transaction.

Artifacts: `.ai-runtime/qa-artifacts/inspection-report/` contains `e2e-results.json`, generated/reviewed-source report JSON, final/preview PDFs, rendered PDF pages, desktop/laptop/mobile/customer screenshots and diagnostic rejected-model samples. This folder is local and ignored by Git.

The latest guardrails were also replayed against the actual persisted Qwen-generated no-finding and reviewed-finding artifacts; both passed. Final target totals: 17 Java tests, 16 Python tests, 10 frontend tests, plus the live browser/API suites and PDF extraction/render inspection.
