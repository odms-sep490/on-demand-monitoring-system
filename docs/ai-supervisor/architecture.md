# ODM AI Supervisor — Kiến trúc tổng thể

## 1. Mục tiêu
Xây dựng hệ thống **ODM AI Supervisor** độc lập với luồng AI phục vụ nghiệp vụ (production AI) trong OnDemand Monitor nhằm giám sát, đánh giá chất lượng 3 lớp, hiệu chuẩn Judge, tối ưu prompt offline (GEPA Pareto) và kiểm toán tập trung trong Admin Portal.

## 2. Sơ đồ kiến trúc
```text
Admin Portal (#portal/admin/ai-ops)
  └── AiOperationsPage (10 tabs: Overview, Registry, Services, Tracing,
                        DeepEval, AI Supervisor, Prompt Lab, Security, Audit, Incidents)
        ↓ REST (/api/admin/ai-ops/*, @PreAuthorize("hasRole('ADMIN')"))
Spring Boot Backend (com.ondemandmonitoring.aiops)
  ├── AiOperationsController / AiOperationsService
  │     ├── Live HTTP Readiness Probes (:8000, :8002, :11434/api/tags)
  │     ├── OS/JVM Telemetry (OperatingSystemMXBean)
  │     ├── DeepEval Artifact Sync (.ai-runtime/deepeval/deepeval-summary.json)
  │     └── Kill Switch, Safe Config Whitelist, Append-only Audits, Incidents
  └── AiSupervisorController / AiSupervisorService
        ├── Bounded Subprocess Invocation -> Python/ai-supervisor/supervisor.py
        ├── Judge Calibration Persistence (ai_judge_calibrations)
        └── Prompt Lab Approval & Rollback (ai_prompt_versions, ai_prompt_experiments)
              ↓
PostgreSQL (add_ai_operations_center.sql + add_ai_supervisor.sql)
Local AI Runtimes: Image Worker (:8000) · AI Worker (:8002) · Ollama (:11434)
```

## 3. Nguyên tắc an toàn
- Supervisor **không bao giờ** chặn hoặc làm gián đoạn luồng AI phục vụ khách hàng/nhân viên nếu offline.
- Không truyền dữ liệu production ra dịch vụ cloud bên ngoài (100% local, không dùng AWS/Bedrock).
- Vi phạm ràng buộc cứng (prompt injection, rò rỉ token, JSON sai, số liệu không có nguồn) luôn do lớp Deterministic phát hiện và không bị điểm LLM ghi đè.
- Thiếu dữ liệu tham chiếu trả về `INSUFFICIENT_EVIDENCE` / `NOT_EVALUATED`, không quy về điểm `0`.
