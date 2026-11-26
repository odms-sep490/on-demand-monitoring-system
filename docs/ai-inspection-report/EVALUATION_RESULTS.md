# Actual local editorial evaluation
Model: odm-order-qwen:3b. Same 10 synthetic Vietnamese cases; actual local Ollama.

| Case | Baseline seconds | After seconds | Changed |
|---|---:|---:|---|
| professional | 10.64 | 4.55 | True |
| shorten | 10.12 | 4.70 | True |
| simplify | 9.17 | 5.17 | True |
| summarize | 8.99 | 4.33 | True |
| caption | 10.48 | 3.44 | False |
| missing | 7.95 | 4.11 | False |
| conflict | 8.81 | 3.84 | False |
| injection | 7.94 | 0.00 | False |
| abnormalities | 9.58 | 4.44 | False |
| long | 32.02 | 4.61 | False |

Median latency: baseline 9.38s; after 4.38s. Local load may affect timings.
Returned structured results 10/10; unchanged/rejected fields are not counted as style improvement. Raw source/proposals and warnings are checked in.
Verification: 13 editorial deterministic tests, 18 generation guard tests, 16 Java InspectionReportTest passed. TypeScript and targeted ESLint passed.
UI: actual FE -> QA Spring -> Python -> local Ollama; five options opened, before/after highlight inspected, Copy (clipboard), Regenerate, Apply, Undo and no-auto-save checked. Synthetic Mission, fixture identity/storage, real isolated PostgreSQL. Report-list response filtered to the chosen real QA report to avoid selecting unrelated QA drafts; model/editor endpoints not mocked. Screenshots/ui-results.json in .ai-runtime/editorial. No production Cognito/S3/payment verification.
DeepEval custom numeric/unit metric: summary-only; does not measure semantic equivalence. No calibrated numeric tone/readability/hallucination score is claimed. No vision model invoked.
Global frontend diff --check also reports pre-existing/unrelated CRLF whitespace in MonitoringChecklistSection.css; that file was not edited by this task.