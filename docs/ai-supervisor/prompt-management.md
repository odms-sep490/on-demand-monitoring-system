# Centralized Dynamic Prompt Management & Hot-Reload

## 1. Luồng phân giải Runtime (Zero-Restart Hot Reload)
1. Admin tạo bản nháp mới (`DRAFT`) tại `#portal/admin/ai-ops/promptLab` — tuyệt đối không ghi đè bản `ACTIVE` đang chạy.
2. Admin kiểm thử cô lập trên **Playground** (gọi Ollama thật, không có side effect nghiệp vụ), phê duyệt (`APPROVED`) và bấm **Kích hoạt** (`ACTIVE`).
3. `PromptManagementService.activateVersion()` chạy trong 1 transaction PostgreSQL:
   - Chuyển bản cũ sang `ARCHIVED` (giữ nguyên bất biến để Rollback).
   - Chuyển bản mới sang `ACTIVE` (ràng buộc `uq_ai_prompt_one_active` đảm bảo tối đa 1 bản ACTIVE/key).
   - Ghi bản ghi append-only vào `ai_prompt_activation_history` và `ai_execution_audits`.
   - Gọi `invalidateCache(promptKey)` ngay lập tức.
4. Các service Java (`AiResourceEstimateService`, `ChecklistImageVisionClient`) nhận bản mới ở request tiếp theo ngay lập tức.
5. Các Python workers (`inspection_report.py`, `description_agent.py`, `conversation_agent.py`) gọi `GET /api/internal/ai/prompts/{key}/active` (xác thực qua `X-Internal-Token`) với `PROMPT_CACHE_TTL_SEC=10` nên tự nhận phiên bản mới trong tối đa 10 giây mà **không cần restart worker**.

## 2. Xử lý sự cố khi Registry tạm gián đoạn
- Ưu tiên dùng bản `ACTIVE` gần nhất trong cache (`staleCacheUsed=true`).
- Không cho phép biến template thực thi code/SQL/shell; từ chối thiếu biến bắt buộc và chặn lưu credentials/private key trong nội dung prompt.
