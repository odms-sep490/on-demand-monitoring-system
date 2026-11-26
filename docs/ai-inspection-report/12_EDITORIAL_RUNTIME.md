# Runtime editorial assistant audit

## Reference research (2026-11-25)
Cloned shallow copies into workspace `.editorial-research`, outside both production repositories. Hidden Unicode characters were removed from the msimchowitz URL.

| Repository | Commit | License observed | Use |
|---|---|---|---|
| skill-smith/wordsmith | 7f7f4c7fbf34f7efb565364e7189850c84d3cda5 | MIT | Read SKILL, command routing and test prompts; inspired narrow edit scope and factual preservation |
| matt-riley/agent-skills | 482f35f6c0e87c1e4ff046111e5f3306de5548d8 | GPL-3.0 | Research only; no copied code or prompt text |
| msimchowitz/writing-skills | 214981fe02326f27b0fc8790d00eb4b731607073 | MIT | Read global writing pass and prompt-improving workflows; inspired coherent paragraph openings |
| ComposioHQ/awesome-claude-skills | be2a406907dbc61b73e6827ded415c96139d13a2 | No root LICENSE found | Research only; content-research-writer source attribution workflow |
| dantnan/reply-better-ai | 9e4624f06889169ffc24e8807586f30440c7a98b | MIT | Read system-prompts.js, review workflow and evaluations; inspired explicit editing versus replying distinction |

All runtime rules and code were authored for OnDemand Monitor. No third party source bundled. Referenced repositories are research material, not instructions executed by the application.

## Architecture
Existing authorized report rewrite controller -> frozen Mission evidence and stored document -> Python skill router -> allowlisted context builder -> compact Vietnamese prompt + one few-shot -> native Ollama JSON schema -> field key and deterministic factual guards -> proposed document -> backend validates evidence and current source -> preview -> explicit apply -> explicit save.

Runtime registry and six skill packages are under `Python/voice-order/editorial_skills`. Each contains runtime prompt, schema, Vietnamese example and rules. `fact-preservation` runs for every action. No model change or paid API. Inference remains ORDER_AGENT_MODEL (default odm-order-qwen:3b).

Only executive summary/unquoted plain section prose is edited for prose actions. Caption edits only captions. Confirmed observations, tables/rich text, technical conclusion, limitations, source IDs, order and roles remain unchanged. This prevents rewriting a legally meaningful conclusion. Unsaved draft must be saved before generating proposals. Video metadata is supported without claiming video/vision analysis.

## Guards and limits
Exact multiset checks protect numbers, dates and units per field, including repeated values. Conservative qualification checks preserve negation, uncertainty and severity terms. Named Mission codes and named locations are checked. Injection markers trigger a warning without a model call. Structured schema rejects unknown/duplicate/missing keys. Invalid proposals never save automatically. Request bounded to 14k text characters / 40 fields / 40 evidence entries, context 8192, output 2400 tokens, 100 second timeout. Caller returns controlled unavailable response on timeout/invalid JSON. Source access and checksum/publication/retention gates remain on backend.

These deterministic guards are NOT a proof of semantic equivalence. They may reject legitimate paraphrases and cannot detect every unsupported qualitative claim or sophisticated injection. Human review remains necessary. No vision model is installed or invoked by this feature. Quality metadata is not a defect finding. Missing findings and equal start/end time produce warnings.

## Evaluation
Frozen baseline and new runtime are measured against the same synthetic Vietnamese dataset using actual local Ollama. Raw artifacts live under `.ai-runtime/editorial`. Checked-in evaluation fixtures contain no production data. Automated measurable signals: numeric/unit preservation, JSON validity, changed/unchanged, length ratio, latency; professional tone/readability/faithfulness require review and are not represented as invented judge scores. Existing DeepEval environment runs a custom deterministic NumericUnitPreservation metric; no paid or LLM judge. Ten checked-in after-results were measured, 10/10 numeric/unit summary preservation. This is not semantic faithfulness. See generated EVALUATION_RESULTS.md for actual results.

UI real-runtime test: e2e/live/editorial.live.mjs uses existing isolated ReportQaServer, not production credentials. Own QA PostgreSQL uses localhost:15549 because Windows blocked 5549; only temporary compiled QA fixture copy changes port. No production DB was modified.

## Files for this upgrade
- Python/voice-order/editorial_runtime.py
- Python/voice-order/editorial_skills/registry.json and six runtime skill packages
- Python/voice-order/editorial_skills/evaluation/{dataset,baseline,after,deepeval-deterministic}.json, run.py, deepeval_metrics.py
- Python/voice-order/test_editorial_runtime.py
- Python/voice-order/inspection_report.py (rewrite dispatch; generation preserved)
- ondemandmonitoring/src/main/java/com/ondemandmonitoring/inspectionreport/InspectionReportController.java
- ondemandmonitoring/src/main/java/com/ondemandmonitoring/inspectionreport/InspectionReportService.java
- Frontend src/features/inspection-report/{ReportStudio.tsx,reportApi.ts,reportStudio.css}
- Frontend e2e/live/editorial.live.mjs

## Activation
Restart existing description/conversation worker and backend; reload frontend. No migration required for this upgrade. No commit/push/deploy.

## Known limitations
No vision/video analysis. Rich text/table blocks and confirmed findings are deliberately not paraphrased. Generic metadata caption fallback does not invent an observed scene. Long text may remain unchanged when shortening would violate deterministic factual guards. Inputs over the bounded context return a validation error instead of silent truncation. No calibrated semantic or professional-tone score claimed. Full natural-language contradiction detection remains limited; equal times and explicit missing metadata warnings are implemented. Full production identity/S3/payment tests remain outside isolated QA.
