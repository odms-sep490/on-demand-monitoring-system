# Evaluation Rubrics, Judge Calibration & GEPA Prompt Optimization

## 1. Quy trình 3 lớp (`Python/ai-supervisor/supervisor.py`)
1. **Deterministic Validators**: kiểm tra prompt injection trong output, rò rỉ secret/token, cú pháp JSON, tuyên bố an toàn/chứng nhận không có nguồn, đối chiếu số liệu & ID nguồn, tính WER cho ASR.
2. **Independent Local LLM Judge**: gọi Ollama với model cấu hình qua `SUPERVISOR_JUDGE_MODEL` (mặc định `qwen3:8b`, khác model target `odm-order-qwen:3b` / `qwen3:1.7b`), cô lập dữ liệu trong thẻ `<UNTRUSTED_DATA>` và ép JSON schema.
3. **Human Review & Calibration**: `run_calibration_suite()` kiểm thử 6 ca chuẩn (positive, wrong numeric claim, invalid JSON, prompt injection, fabricated resource ID, missing reference abstention).

## 2. Tối ưu Prompt đa mục tiêu (`Python/ai-supervisor/prompt_optimizer.py`)
- 4 mục tiêu: **Quality** (tối đa hóa), **Tokens** (tối thiểu hóa), **Error Rate** (tối thiểu hóa), **Latency** (tối thiểu hóa).
- Loại bỏ mọi ứng viên có `hard_constraint_violations > 0`, `security_violations > 0`, hoặc hồi quy tỷ lệ lỗi `> 5%` trước khi tính Pareto frontier.
- Mọi thay đổi prompt đều phải qua Admin bấm **Phê duyệt & kích hoạt** tại tab **Prompt Lab (GEPA)** và hỗ trợ **Rollback** 1 chạm kèm audit log.
