# Local order-conversation release gate

Run from the backend repository root. Credentials are process environment variables
`ORDER_AGENT_TEST_EMAIL` and `ORDER_AGENT_TEST_PASSWORD`; never put tokens in reports.

`environment.py` copies the existing local catalog to an isolated QA schema. The
`deepeval-local` Spring profile refuses a non-local database or a non-QA schema.
Start the QA backend with `start-local.ps1` and the existing frontend on 5174 with
`VITE_API_BASE_URL=http://localhost:8081`. The real worker remains on 8002.

Commands (using `.ai-runtime/deepeval-env/Scripts/python.exe`):

- `qa/order_conversation_eval/cases.py`: freeze exactly 100 cases and thresholds.
- `qa/order_conversation_eval/run.py`: real HTTP/DB cases; `--only D` or `--failed`
  replays a subset and preserves old evidence in history.
- `qa/order_conversation_eval/metrics.py`: native DeepEval metrics, strictly local
  Ollama judge. No default paid provider. Judge errors are reported, never passed.
- `qa/order_conversation_eval/report.py`: merge all evidence, rejecting stale judge
  scores after a case rerun. BUSINESS_PASS alone is not a verified final pass.
- Frontend: `npx playwright test --config playwright.deepeval.config.ts` with
  `ORDER_AGENT_API_URL=http://localhost:8081`,
  `ORDER_AGENT_WEB_URL=http://localhost:5174`, and report/output destinations set
  to the backend `.ai-runtime/deepeval` directory.

The manifest's baseline HTTP 200 is the owner session creation call. Negative
operation contracts are enforced separately in `run.py`: anonymous 401/403,
wrong owner/role 403, stale revision or changed request replay 409, invalid
context/message/expired session 400. Invalid drafts return HTTP 200 with
`readyForReview=false` and actionable fields. No case submits an order.

H10 injects a browser transport failure; it does not mock an Agent success.
Unit tests with mocks are recorded only as additional regression, not as real
LLM cases. Browser H01/H06/H07 use the production FormState and canonical API;
H02/H08 exercise real chat persistence and explicit confirmation.

All evidence is ignored under `.ai-runtime/deepeval`. Frozen cases and thresholds
cannot be edited after execution. Scores include model, reason, threshold and an
execution hash. A missing score, failed metric, absent browser run or blocked
judge prevents a 100/100 claim. Local model self-judging is imperfect; mandatory
deterministic checks take precedence for IDs, counts, state, revision and orders.
