# Kiểm tra chất lượng ảnh — local / Docker

## Chức năng đang chạy

Đo ảnh bằng CPU: tính toàn vẹn JPEG/PNG, checksum/MIME, độ chi tiết sau chuẩn hóa, độ sáng/vùng clipping, kích thước, dHash. Năm profile có ngưỡng tham khảo riêng và version `1-provisional`. Không có điểm confidence giả. `PASS` chỉ là đạt các phép đo đã áp dụng. Không có checkpoint chất lượng drone được xác minh: PaddleClas classifier luôn `NOT_CONFIGURED`.

## Local hiện tại

1. Chạy migration `ondemandmonitoring/src/main/resources/seeddata/migrations/add_image_quality.sql` bằng tài khoản DB có quyền schema trên môi trường local/test. Đã áp dụng tại localhost trong phiên triển khai, không chạy production.
2. `.env` project: `IMAGE_QUALITY_ENABLED=true`, `IMAGE_QUALITY_WORKER_URL=http://127.0.0.1:8000`, `IMAGE_QUALITY_INTERNAL_TOKEN` là secret khớp worker; giới hạn batch mặc định 20, ảnh 8 MB. Không commit `.env`.
3. Run **Start AI Worker** (WSL Ubuntu-24.04) và restart Spring Boot. Worker hiện có thêm `/quality/ready` và `/quality/analyze` riêng, dùng cùng cổng Open-CD. Thư viện metrics đã có trong môi trường WSL.
4. CUSTOMER: mở ảnh đã được bàn giao bản gốc trong Thư viện kết quả, chọn **Kiểm tra chất lượng AI**. Có thể chọn ảnh tiếp/trước trong viewer. Trang chi tiết ảnh cũng có panel.
5. STAFF/MANAGER: gallery **Media nhiệm vụ → Hỗ trợ kiểm duyệt chất lượng ảnh**. Mở bảng, kiểm tra hàng loạt hoặc từng ảnh, lọc, xem lịch sử, chọn quyết định nhân viên và ghi chú. Quyền STAFF phụ thuộc inspector/review stage hiện có; backend là nơi quyết định quyền.

Quality service chỉ đọc original qua `IMediaObjectStorage.open`; không nhận URL/file từ customer. Không thay đổi media status, mission status, payment hoặc approval bàn giao.

## Docker CPU độc lập

Chạy tại `Python/change-detection`:

```sh
docker build -f Dockerfile.quality -t odms-image-quality .
docker run --rm --name odms-image-quality --env-file .env -p 127.0.0.1:8000:8000 odms-image-quality
```

`.env` trong thư mục worker chỉ cần `IMAGE_QUALITY_INTERNAL_TOKEN` và optional giới hạn size. Không chạy cùng lúc hai worker chiếm cổng 8000. Triển khai container nội bộ dùng network private và URL worker nội bộ; không publish ra Internet. Docker CPU metrics không cần Torch/Paddle/model.

## Trạng thái / khôi phục

PENDING/PROCESSING/COMPLETED/FAILED/CANCELLED tách biệt PASS/REVIEW_REQUIRED/FAIL/INDETERMINATE. Gửi lại cùng request key trả cùng job; profile khác cùng key trả conflict. Mỗi lần kiểm tra mới tạo lịch sử mới. Worker down retry tối đa 3; lease quá 3 phút được phục hồi, token lease ngăn attempt cũ ghi đè. Event audit ghi request/process/result/retry/review/cancel.

Nhận diện trùng chỉ so với ảnh đã kiểm tra trong cùng mission, không tự coi chụp lặp lại là lỗi. File hỏng là FAIL có căn cứ; mờ/tối/cháy sáng/kích thước theo ngưỡng chưa calibrated là REVIEW_REQUIRED. Chưa map yêu cầu resolution riêng từ từng hợp đồng thì ghi INDETERMINATE cho tiêu chí đó.

## Tests

```sh
python -m unittest test_quality -v
```

BE: `mvnw.cmd -Dtest=ImageQualityServiceTest test`. Integration `ImageQualityPostgresTest` chỉ chạy khi `QUALITY_TEST_JDBC_URL` là localhost, `QUALITY_TEST_DB_USER/PASSWORD`, `QUALITY_TEST_IMAGE` và `IMAGE_QUALITY_INTERNAL_TOKEN` được cung cấp. Test tạo schema tạm riêng rồi xóa; dùng HTTP metrics worker thật và ảnh fixture, media storage/auth dependencies kiểm thử bằng boundary doubles.

FE: `npx vitest run src/features/media/components/ImageQualityPanel.test.tsx src/features/media/components/MissionQualityPanel.test.tsx`; `npm run typecheck`.

## Rollback

Tắt `IMAGE_QUALITY_ENABLED`, restart BE. Giữ quality history phục vụ audit hoặc backup trước khi xóa; không đổi dữ liệu source/delivery. Nếu cần gỡ hoàn toàn, xóa riêng bảng `image_quality_event`, `image_quality_review`, `image_quality_check` theo thứ tự sau khi xét retention. Không drop tự động.

## Nâng cấp PaddleClas

Source release/2.6 Apache-2.0: https://github.com/PaddlePaddle/PaddleClas/tree/release/2.6
Inference export docs: https://github.com/PaddlePaddle/PaddleClas/blob/release/2.6/docs/en/inference_deployment/python_deploy_en.md
Không dùng ImageNet labels thay quality labels. Cần dataset drone được phép sử dụng, nhãn nhiều lỗi trên một ảnh, train/validate split theo mission/camera/site (tránh leakage), export và pin Paddle runtime/preprocess. Multi-label dùng output/threshold riêng đã calibrated cho từng nhãn; softmax single-label không phù hợp ảnh vừa mờ vừa tối. Model activation còn cần license dataset/weights, checksum từ nguồn tin cậy và holdout report. `PaddleQualityClassifier` hiện là boundary NOT_CONFIGURED để không giả vờ đã tích hợp quality model.
