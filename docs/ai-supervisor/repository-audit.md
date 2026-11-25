# Repository Audit & Models Discovered

## Models & Workers thực tế trong hệ thống
| Thành phần | Model / Runtime | Endpoint nội bộ | Trạng thái triển khai |
|---|---|---|---|
| Change Detection | Open-CD Changer (PyTorch) | `http://localhost:8000/ready` | Đã triển khai |
| Image Quality | Image Quality Worker (OpenCV/PyTorch) | `http://localhost:8000/quality/ready` | Đã triển khai |
| Description AI | `qwen3:1.7b` (Ollama) | `http://localhost:8002/conversation/health` | Đã triển khai |
| Order Consultant & Report Studio | `odm-order-qwen:3b` (Ollama) | `http://localhost:8002/conversation/health` | Đã triển khai |
| Speech Recognition | Qwen3-ASR (PyTorch) | `http://localhost:8002/conversation/health` | Đã triển khai |
| Text-to-Speech | ZeroTTS / VieNeu (PyTorch) | `http://localhost:8002/conversation/health` | Đã triển khai |
| Local LLM Runtime | Ollama | `http://localhost:11434/api/tags` | Đã triển khai |
| Weather Advisor / Crew Optimizer | — | — | Chưa triển khai (đề xuất tương lai) |

## Cải tiến Launcher (`scripts/start-ai-services.ps1`)
- Kiểm tra `Get-NetTCPConnection -State Listen` trước khi khởi động port `8000` và `8002` để ngăn xung đột cổng (`port 8002 conflict`).
- Phân biệt rõ trạng thái `READINESS`, `MODEL_LOADED`, `DEGRADED`, `OFFLINE` và khử trùng lặp log khi trạng thái không đổi.
