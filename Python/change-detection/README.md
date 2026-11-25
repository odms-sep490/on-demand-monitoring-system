# AI phân tích thay đổi

Khách hàng mở **Thư viện kết quả → AI phân tích thay đổi**. Spring Boot kiểm tra quyền tải ảnh gốc và quản lý hàng đợi PostgreSQL. Worker nội bộ căn chỉnh ảnh sau vào tọa độ ảnh trước, chạy Open-CD theo ô 512 × 512, rồi trả ảnh đã căn chỉnh, mặt nạ PNG, overlay PNG và tỷ lệ pixel thay đổi trên phần ảnh chồng khớp. Tỷ lệ này không đo tiến độ công trình.

Job và ảnh đầu vào tạm nằm trong bảng PostgreSQL riêng. Ảnh tạm được giải phóng khi job kết thúc. Kết quả tự hết hạn sau 30 ngày. Xóa job không ảnh hưởng sản phẩm bàn giao. Khi gỡ tính năng, dừng mọi bản Spring Boot có worker trước, sao lưu nếu cần giữ lịch sử, rồi chạy `DROP TABLE ai_change_analysis;` để rollback schema riêng.

## Cấu hình

### Run trực tiếp từ IntelliJ bằng WSL (không cần Docker)

Chọn **Start AI Worker**. Launcher `scripts/start-change-ai.ps1` chạy WSL distro `Ubuntu-24.04` và `scripts/wsl-change-ai.sh`. Lần đầu tự cài Python 3.11, môi trường riêng, dependencies và tải checkpoint ChangerEx R18 LEVIR-CD từ link trong model zoo chính thức. Các tệp nằm trong `~/.local/share/ondemand-change-ai` của WSL, không thay môi trường Python drone. Những lần sau dùng lại môi trường/model. Cần mạng cho lần cài đầu tiên. Dừng worker bằng Ctrl+C trong cửa sổ Run. **Start Drone Stack** vẫn độc lập.

Worker đọc `OPENCD_TOKEN` từ `.env` project, dùng config trong checkout Open-CD đã pin và CPU; không cần nhập đường dẫn `/models` của Docker. SHA-256 được lưu sau download để phát hiện thay đổi tệp ở các lần chạy sau; đây không phải checksum do tác giả model công bố. Model dùng thử local, quyền checkpoint/dataset cho triển khai thương mại cần xác minh riêng.

Kiểm tra `http://localhost:8000/ready` phải trả `READY`. Đã smoke-test trên WSL với một cặp ảnh tổng hợp giống nhau: endpoint `/infer` trả 200, tỷ lệ thay đổi 0; không phải đánh giá độ chính xác trên ảnh công trình thực.

- Model ứng viên: ChangerEx ResNet-18, 512 × 512, LEVIR-CD theo [hướng dẫn Open-CD](https://github.com/likyoo/open-cd/blob/main/docs/inference.md). Source Open-CD tại commit 790a1972b538baaaffd4c3022a4d9afaa3f861e5 có giấy phép Apache-2.0. Cần xác minh quyền với checkpoint, dataset và gói phụ thuộc trước khi dùng với dữ liệu khách hàng.
- Gắn checkpoint và config đã phê duyệt bằng ổ đọc riêng. Chỉ dùng checkpoint đáng tin cậy vì PyTorch có thể giải tuần tự mã khi nạp file. Kiểm tra SHA-256 từ nguồn đáng tin cậy.
- Worker nhận OPENCD_CONFIG, OPENCD_CHECKPOINT, OPENCD_CHECKPOINT_SHA256, OPENCD_DEVICE (cpu hoặc cuda:0) và OPENCD_TOKEN. Spring nhận CHANGE_ANALYSIS_WORKER_URL và CHANGE_ANALYSIS_TOKEN tương ứng. Giữ token trong secret store.
- Đặt giới hạn multipart Spring tối thiểu 8 MB mỗi file và 17 MB mỗi request, cùng giới hạn tương ứng ở reverse proxy. Worker giới hạn 2 ảnh JPEG/PNG, 256–4096 pixel mỗi chiều, 8 MB mỗi ảnh.
- GET /health kiểm tra tiến trình. GET /ready trả 503 NOT_CONFIGURED khi thiếu cấu hình hoặc model không khởi tạo được. Không có kết quả giả.

## Chạy Docker nội bộ

Từ thư mục này, chạy `docker build -t odms-change-detection .`. Đưa container vào cùng mạng riêng với Spring, gắn checkpoint bằng volume chỉ đọc và truyền biến môi trường. Không publish cổng worker. Khi chạy Spring ngoài Docker để phát triển, chỉ bind worker vào 127.0.0.1. Đường dẫn config điển hình nằm dưới /opt/open-cd/configs/changer/ hoặc tại volume config được duyệt.

Với GPU, build lại bằng TORCH_INDEX và MMCV_INDEX cho cùng phiên bản Torch 2.0.0 và CUDA 11.8, cấp GPU cho container qua NVIDIA Container Toolkit và đặt OPENCD_DEVICE=cuda:0. Kiểm tra /ready và chạy fixture trên phần cứng thật trước khi đưa vào sử dụng.

## Chạy local

Dùng môi trường Linux x86-64 với Python 3.11. Cài Torch 2.0.0 và torchvision 0.15.1 từ kho wheel CPU PyTorch cùng wheel MMCV 2.0.1 theo lệnh trong Dockerfile. Cài requirements.txt, clone Open-CD tại commit đã ghi và đặt PYTHONPATH trỏ tới thư mục clone. Sau khi đặt các biến môi trường, chạy `uvicorn app:app --host localhost --port 8000 --workers 1`. Đặt CHANGE_ANALYSIS_WORKER_URL=http://localhost:8000 cho Spring và khởi động Spring cùng React bằng lệnh phát triển của từng repo.

## Kiểm tra bằng ảnh drone

Chọn hai ảnh chụp cùng công trình, gần cùng góc nhìn. Bắt đầu với JPEG/PNG dưới 8 MB. Tạo job và xem chuyển từ đang chờ sang đang phân tích và hoàn tất. Dùng thanh kéo kiểm tra các cạnh cố định trùng vị trí, bật/tắt vùng đánh dấu và đối chiếu với thay đổi nhìn thấy. Thử một ảnh khác khu vực để kiểm tra hệ thống từ chối khi không căn chỉnh được. Các cảnh có ít điểm đặc trưng, sai khác góc lớn hoặc parallax có thể bị từ chối hoặc cho kết quả kém.

`python -m unittest test_app` chạy các kiểm tra không dùng checkpoint. Docker build, CPU/GPU inference và độ chính xác theo bộ ảnh thực tế cần xác minh với checkpoint đã duyệt.
