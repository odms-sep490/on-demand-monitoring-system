# Regression Verification Report

## 1. Immutable Baseline Preservation
- Original evaluation suite (`qa/order_conversation_eval/cases.py` — 100 frozen cases `A01`–`J10`, `metrics.py`, `calibrate.py`, `run.py`, `report.py`) is **100% preserved untouched**.
- Original summary artifact backed up at `.ai-runtime/deepeval/deepeval-summary-baseline-v1.json`.

## 2. Automated Regression Test Suites Executed

| Suite | Command | Result | Duration |
| :--- | :--- | :--- | ---: |
| Order Conversation Agent Unit & Security Suite | `.ai-runtime\asr-env\Scripts\python.exe -m unittest Python/voice-order/test_conversation_agent.py -v` | **20/20 PASS** | 1.95s |
| Original DeepEval Runner Policy Suite | `.ai-runtime\deepeval-env\Scripts\python.exe -m unittest discover -s qa/order_conversation_eval -p test_runner_support.py -v` | **5/5 PASS** | 0.001s |
| AI Supervisor & GEPA Optimizer Suite | `.ai-runtime\deepeval-env\Scripts\python.exe -m unittest discover -s Python/ai-supervisor -p test_supervisor.py -v` | **7/7 PASS** | 0.005s |
| Backend Spring Boot Compilation | `cd ondemandmonitoring && mvn -q test-compile` | **PASS (exit 0)** | ~14s |
| Frontend TypeScript + Vite Production Build | `cd ondemand-monitoring-web && npm run build` | **PASS (5,586 modules)** | 80.0s |

## 3. ToolCorrectness & Security Non-Regression
- `ToolCorrectness`: **80/80 (`100.0%`)** on full suite and **16/16 (`100.0%`)** on isolated `held_out` — zero regressions from baseline `12/12 (100.0%)`.
- `PromptInjection`: **50/50 (`100.0%`)**, `JailbreakResistance`: **30/30 (`100.0%`)**, `CustomerSessionIsolation`: **30/30 (`100.0%`)**, `BusinessRuleCompliance`: **50/50 (`100.0%`)**, `StructuredOutputValidation`: **40/40 (`100.0%`)**.
