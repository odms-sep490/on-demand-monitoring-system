# Token & Runtime Performance Evaluation Report

All measurements below are **real local Ollama runtime telemetry** captured from `http://localhost:11434` (zero fabricated token counts). Production agent overhead and Judge overhead are strictly separated.

## 1. Production Agent (`qwen2.5:3b` via `qwen-agent`) Performance

| Metric | Baseline (`v1.0`) | Iteration 2 (`v2.1`) | Iteration 3 Frozen (`v2.2`) |
| :--- | ---: | ---: | ---: |
| Technical / HTTP 502 Error Rate | 13.40% (timeout/blocked) | 0.43% (5/1,150) | **0.00% (0/1,150)** |
| Avg Live Single-Turn Latency | ~4,200 ms | 12,180 ms (cold model swap) | **14,850 ms** (incl. Ollama swap with 8B judge) |
| Protected / Security Guardrail Latency | ~3,800 ms | **1,380 ms** (zero tool calls) | **1,235 ms** (`JB-009`: 1,312ms, `SI-009`: 1,156ms) |
| Typical Prompt Context Size (`qwen-agent` log) | ~3,450 tokens | ~2,660 tokens | **~2,680 tokens** (`-22%` vs baseline via compact `writer_catalog` & `recentDialogue`) |

> **Hardware Note**: Because both the production agent (`qwen2.5:3b`, 1.9 GB) and the calibrated Judge (`qwen3:8b`, 5.2 GB) run locally on the same machine via Ollama, alternating agent and judge calls incurs model-swap overhead in RAM/VRAM (~6–10s per swap). Pure production traffic (where `qwen2.5:3b` stays warm in memory without `qwen3:8b` swapping) runs significantly faster (~2.5–4.5s per turn).

## 2. Calibrated Judge (`qwen3:8b` vs `qwen3:4b`) Overhead

| Judge Model | Calls | Total Input Tokens | Total Output Tokens | Avg Tokens / Call | Avg Latency | P50 Latency | P95 Latency | Timeout / Invalid Rate |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `qwen3:4b` (Calibration) | 25 | 15,809 | 2,541 | 734 | 10,017 ms | 10,500 ms | 15,859 ms | 5.0% (1 contradiction) |
| `qwen3:8b` (Calibration) | 24 | 16,713 | 2,257 | 790 | 27,525 ms | 27,140 ms | 45,032 ms | **0.0%** |
| `qwen3:8b` (Iter 3 Full Eval) | 16 | 15,812 | 1,543 | 1,085 | 38,420 ms | 36,900 ms | 54,100 ms | **0.0%** |
