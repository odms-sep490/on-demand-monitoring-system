# Smart Quote Assistant — implementation and verification

Status: PARTIALLY IMPLEMENTED. Code for analysis, scenarios and Qwen chat is present. Authenticated full-stack E2E against real test orders is not verified; no test credentials were provided. Browser harness tests use API doubles. Real model checks use backend fixtures; they are not real-order E2E.

## Files

Backend:
- finance/controller/SmartQuoteController.java
- finance/service/impl/SmartQuoteService.java
- finance/service/impl/QuoteScenarioCalculator.java
- finance/service/impl/QuoteAgentClient.java
- finance/SmartQuoteServiceTest.java and QuoteScenarioCalculatorTest.java under src/test/java
- Python/voice-order/app.py, quote_assistant.py, test_quote_assistant.py, requirements-quote.txt
- scripts/start-quote-assistant.ps1

Frontend:
- src/features/finance/SmartQuoteAssistant.tsx, .css, .test.tsx
- Existing CustomerFinance/components/QuoteSection.tsx and ManagerPricing/components/QuoteSummary.tsx integration
- e2e/smart-quote.e2e.ts and e2e/harness/smart-quote.{html,tsx}

Pre-existing application.yaml, OrderPreReviewPanel and OrderReviewPage changes were preserved. Local ignored .env contains the generated internal token; do not commit it.

## Source audit and pricing

QuoteService.getCurrentForCustomer remains the authoritative ownership/publication gate. Managers/admins retain existing pricing permission; STAFF has not been granted new manager rights. Quotes already have quoteVersion, pricingRevision, checklistRevision and updatedAt. No schema migration is needed.

Money comes from persisted customer-safe quote projections or PricingEngine (RuleBasedPricingEngine). Its version-2 catalog path uses BaseChecklistPriceCalculator.withMediaCharges and frozen included counts/extra-image/extra-video prices. Scenario copies are detached OrderChecklistItem instances; no repository save, contract or payment call is made.

Supported: unchanged quote, explicit image/video counts on one selected catalog item, and remove-video text when a single quote item can be identified. Engine recalculates the entire scenario. Prices are estimates requiring the existing re-quote workflow.

Manual: duration changes (no independent duration contract implemented), ambiguous free text, missing media unit price, custom/manual items, legacy pricing snapshots, unrecognized item, or any baseline engine amount differing from the official quote. A lower count within an included package can legitimately have zero savings. No arbitrary subtraction of a quote line is used.

Calculation comparison returns backend BigDecimal total and difference. Quote revision mismatches reject the request. UI checks revisions again on focus/30-second refresh and clears stale comparisons and AI text.

## API

GET /api/{customer|manager}/orders/{orderId}/quotes/{quoteId}/analysis
POST /api/{customer|manager}/orders/{orderId}/quotes/{quoteId}/compare
POST /api/{customer|manager}/orders/{orderId}/quotes/{quoteId}/assistant/messages

Analysis includes approved/saved totals, line items, source version, warnings, media options and factors. Comparison accepts 2–3 options with a bound revision; change fields are itemId, images, videos, durationSeconds. No mutation endpoint is exposed to the agent.

## Qwen runtime and safety

Shared agent location: Python/voice-order. No prior runnable .py service source was present at audit time (only the old runtime artifacts); the new entrypoint mounts the quote router there rather than introducing another model server. Preserve/reconcile any separately maintained Voice Order entrypoint before combining its routes.

Runtime: existing Ollama localhost:11434, model qwen3:0.6b. Qwen-Agent localhost:8002, CPU/local Ollama inference. Start via scripts/start-quote-assistant.ps1; no Docker needed. Restart Spring after compilation. Token comes from QUOTE_ASSISTANT_TOKEN or local ignored .env. Production should inject environment values explicitly.

Read-only tools: getQuoteSummary, getQuoteLineItems, getPricingFactors, compareQuoteOptions, getQuoteRevision, getAvailableQuoteChanges. Tools call only bound Spring analysis/compare URLs with the caller's bearer token. Python has no database access; internal-token authentication and path whitelist prevent arbitrary endpoints. Spring enforces role and ownership again.

LLM text passes numerical grounding, including normalization of thousands separators; currency-marked values must match monetary fields from backend tool output. Invalid model numbers return an unavailable/error response rather than invented money. This guards numbers, not perfect semantic interpretation; users still follow the official quote workflow.

Spring has bounded concurrency, a short request cooldown, a 90-second request timeout, and no retries. Qwen requests use a 20-second timeout and no retries. Unavailable model or rejected output produces explicit unavailable UI.

## Verification evidence

- Maven selected tests: SmartQuoteServiceTest (5), QuoteScenarioCalculatorTest (5), BaseChecklistPriceCalculatorTest (3), RuleBasedPricingEngineTest (4): 17 PASS, exit 0. Includes engine delegation, detached originals/no save, unsupported duration, missing price, baseline adjustment preservation, stale quote and service permission delegation. QuoteScenarioCalculator unit cases use a mock engine; existing pricing regression cases exercise the real calculators.
- Frontend build: npm run build: PASS, exit 0 (existing bundler eval warnings).
- Frontend panel test: vitest run src/features/finance/SmartQuoteAssistant.test.tsx: 1 PASS, exit 0.
- Browser UI harness: E2E_PORT=5188 playwright test e2e/smart-quote.e2e.ts --workers=1: 2 PASS, exit 0. CUSTOMER/MANAGER, breakdown, manual comparison, chat-unavailable recovery, widths 390/768/1366 and horizontal-overflow checks. API responses are test doubles; screenshot artifacts are under frontend/test-results/smart-quote-{customer,manager}.png.
- Python: TEST_REAL_QUOTE_LLM=1 python -m unittest test_quote_assistant -v: 7 PASS, exit 0. Includes internal authentication, forbidden mutation URL, hallucinated-money rejection, stale revision, registered read-only tools, real Qwen Vietnamese inference and a real tool call (observed count 1). Backend data in these model tests is a test fixture.

## Remaining verification/blockers

- Real CUSTOMER/MANAGER test credentials and a confirmed non-production test order are required for authenticated full-stack browser E2E and cross-customer access tests. Reply "ok" does not supply these credentials. No login bypass or production quote mutation was attempted.
- No claim of real-order AI E2E or fully completed feature is made.
- Duration repricing needs an existing business rule/API contract before it can be automated; returns manual quote today.
- Local model is small; response quality remains subject to review. No official price or approval is delegated to the model.

No commit, push, production deployment or database migration was performed.

## Follow-up verification — 2026-11-22

- Added QuoteAgentClientIntegrationTest: 3 PASS, exit 0. Uses a real loopback HTTP server as the worker test double and verifies internal token, caller bearer token, bound customer path, and early rejection on stale revision/permission failure. It is not authenticated application E2E.
- Re-ran the 17 backend pricing/assistant tests: PASS, exit 0.
- Added rejection of spelled-out/scaled monetary claims and unsupported percentages in model text. Python latest run: 6 PASS, 2 real-model tests SKIPPED, exit 0.
- An explicitly enabled real-model rerun failed with connection errors: Ollama no longer listens on 11434; Ollama installer processes are present. Earlier inference and real tool-call PASS results remain valid historical evidence. The current environment cannot be called AI-ready until Ollama is restored and readiness is rechecked.
- Existing unrelated whitespace in application.yaml/OrderReviewPage was not changed. No test credentials were supplied beyond an acknowledgement; real-order authenticated browser E2E remains blocked.

Frontend final build on 2026-11-22: TypeScript and Vite completed successfully (existing third-party bundler warnings).

## Local runtime restored — 2026-11-22

- Ollama was running with `qwen3:0.6b`, but the quote runtime was not listening on 8002. Started the existing quote runtime and left it running locally.
- Added `/quote-assistant/health`, which checks whether the configured model is available. Live response: `status=ready`, `model=qwen3:0.6b`.
- Added the shared IntelliJ run configuration `.run/Start_Quote_AI.run.xml` (`Start Quote AI`). The startup script checks the Python environment, enables UTF-8 output, and reuses an already healthy runtime instead of binding the port twice.
- Python verification with `PYTHONUTF8=1 TEST_REAL_QUOTE_LLM=1`: 8 tests PASS, including real local Qwen inference and a real read-tool call using backend fixtures. The initial run's console encoding failure was resolved by UTF-8 output.
- These checks do not establish authenticated real-order browser E2E. No quote was changed or sent as part of runtime verification.

## Customer request guidance — live verification 2026-11-22

- Customer request help uses `/api/customer/request-assistant/messages` and server-built customer pricing preview context. Existing order/item identifiers are rejected for this creation-only endpoint.
- Fixed the running Python service missing the new router by restarting it. Reloaded the backend with the new controller and the same local dotenv values (without shell quote characters).
- The small local model invented an amount in the initial draft test. Request guidance now receives qualitative pricing factors and verified business rules; numerical values remain owned by the price engine and displayed on the form. Unsupported numeric output and explicit claims that manager confirmation is unnecessary are rejected.
- Python: 12 tests PASS, including live local inference for all three suggested customer questions, quote inference, and a real read-only tool call. Backend reports: 3 customer controller tests and 4 agent HTTP tests PASS. FE TypeScript PASS.
- Authenticated browser check on the existing `Seed Customer 1` session: opened the current draft, clicked “Vì sao có chi phí này?”, and observed the real AI explanation referring to the selected factory inspection items and the manager's official quote confirmation. No order was submitted or edited during this check.
- Browser proof: frontend `test-results/customer-service-ai-ready.png`. The draft-help path is verified; saved-quote, contract/payment, and cross-customer browser E2E are not claimed by this check.
