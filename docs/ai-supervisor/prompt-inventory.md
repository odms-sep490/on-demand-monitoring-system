# AI Prompt Inventory — OnDemand Monitor

Kiểm kê toàn bộ prompt chỉnh sửa được trong mã nguồn thực tế (Java Backend & Python AI Workers) trước khi di chuyển sang **Centralized Dynamic Prompt Registry**.

## 1. Các prompt chỉnh sửa được (7 Prompt Keys đã di chuyển vào PostgreSQL)

| Prompt Key | Tính năng AI | Tệp nguồn gốc | Hàm / Hằng số gốc | Model | Loại | Biến động | Hợp đồng đầu ra |
|---|---|---|---|---|---|---|---|
| `order.consultant.router` | Order Consultant (Tool Router) | `Python/voice-order/conversation_agent.py` | `router_prompt` (line 409) | `odm-order-qwen:3b` | `SYSTEM` | Không (tool calls JSON) | Qwen-Agent tool call |
| `order.consultant.system` | Order Consultant (Response Writer) | `Python/voice-order/conversation_agent.py` | `writer` `system_message` (lines 610–629) | `odm-order-qwen:3b` | `SYSTEM` | `{{today}}`, `{{customer_message}}` (optional) | JSON `Turn` schema (`assistantMessage`, `proposedChanges`, `requestedUiAction`) |
| `order.description.system` | Description AI | `Python/voice-order/description_agent.py` | `prompt` trong `generate()` (lines 117–127) | `qwen3:1.7b` | `SYSTEM` | `{{required_objectives}}` (optional) | JSON `{orderDescription, flightPurposeDraft}` |
| `report.inspection.system` | Report Studio (Báo cáo giám sát AI) | `Python/voice-order/inspection_report.py` | `SYSTEM` (lines 112–122) | `odm-order-qwen:3b` | `SYSTEM` | `{{rewrite_instruction}}` (optional) | JSON `Document` schema |
| `supervisor.output.judge` | ODM AI Supervisor (LLM-as-a-Judge) | `Python/ai-supervisor/supervisor.py` | `system_prompt` trong `_call_local_judge()` | `qwen3:8b` | `JUDGE` | `{{feature}}` (optional) | JSON verdict schema |
| `mission.resource-estimate.system` | Mission Device Resource Estimator | `ondemandmonitoring/.../ai/service/AiResourceEstimateService.java` | `SYSTEM_PROMPT` (lines 21–26) | OpenRouter Chat | `SYSTEM` | Không | JSON `AiResourceEstimateContent` |
| `checklist.image-vision.system` | Checklist Image Vision Assessment | `ondemandmonitoring/.../ai/client/ChecklistImageVisionClient.java` | `SYSTEM_PROMPT` (lines 46–55) | OpenRouter Vision | `SYSTEM` | Không | JSON findings per criterion code |

## 2. Các model KHÔNG sử dụng text prompt chỉnh sửa được
Các model sau quản lý tham số suy luận qua **Model Registry → Cấu hình** (không tạo prompt giả):
- **Open-CD Changer** (`open-cd-changer`): PyTorch Computer Vision checkpoint (`threshold`, `timeoutSec`).
- **Image Quality Worker** (`image-quality-worker`): OpenCV/PyTorch (`blurThreshold`, `timeoutSec`).
- **Qwen3-ASR** (`qwen3-asr`): Speech-to-Text (`language`, `timeoutSec`).
- **ZeroTTS (VieNeu)** (`zerotts`): Text-to-Speech (`sampleRate`, `timeoutSec`).

## 3. Ollama Modelfile Audit
- Không có tệp `Modelfile` tùy biến nào trong repository. Toàn bộ system instructions được truyền ở runtime qua chat messages (`role: system`), đảm bảo Admin cập nhật prompt trên PostgreSQL có hiệu lực tức thì mà không cần rebuild model Ollama.

## 4. Ngoại lệ bất biến (Không cho Admin ghi đè)
- Quy tắc xác thực nguồn `validate_references()` (`inspection_report.py`), `validate_output()` (`description_agent.py`), `CatalogTool` allowlist (`conversation_agent.py`), JSON Schema constraints, và Spring Security `@PreAuthorize` luôn được thực thi độc lập bởi backend.
