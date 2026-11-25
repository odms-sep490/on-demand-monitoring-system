# Hướng dẫn chạy & Demo ODM AI Supervisor

## Biến môi trường
- `IMAGE_AI_URL` (mặc định `http://localhost:8000`)
- `DESCRIPTION_AI_URL` (mặc định `http://localhost:8002`)
- `OLLAMA_URL` (mặc định `http://localhost:11434`)
- `SUPERVISOR_JUDGE_MODEL` (mặc định `qwen3:8b`)
- `AI_SUPERVISOR_DIR` (mặc định `../Python/ai-supervisor`)
- `AI_EVAL_SUMMARY_PATH` (mặc định `../.ai-runtime/deepeval/deepeval-summary.json`)

## Lệnh khởi động & kiểm thử
```powershell
# 1. Khởi động AI Workers (có chống trùng port 8000/8002)
.\on-demand-monitoring-system\scripts\start-ai-services.ps1

# 2. Chạy unit test Python AI Supervisor & Prompt Optimizer
python -m unittest discover -s on-demand-monitoring-system\Python\ai-supervisor -p "test_*.py" -v

# 3. Khởi động Spring Boot Backend & Frontend
# Truy cập Admin Portal -> #portal/admin/ai-ops
```

## Kịch bản Demo bảo vệ đồ án
1. Mở **Tổng quan AI**: xem trạng thái live của 6 model, CPU/RAM/JVM thực tế, lưu lượng 7 ngày.
2. Mở **Model Registry**: bật/tắt **Kill Switch** cho 1 model, bấm **Probe** kiểm tra readiness live.
3. Mở **Đánh giá AI**: bấm **Đồng bộ kết quả DeepEval** và **Xuất báo cáo JSON**.
4. Mở **AI Supervisor**: bấm **Chạy Judge Calibration** để xem độ đồng thuận 100% trên bộ ca chuẩn (kể cả ca chống prompt injection và ca `INSUFFICIENT_EVIDENCE`).
5. Mở **Prompt Lab (GEPA)**: so sánh Baseline `v1.0` vs Candidate `v1.1-gepa`, bấm **Phê duyệt & kích hoạt** rồi thử **Rollback**.
