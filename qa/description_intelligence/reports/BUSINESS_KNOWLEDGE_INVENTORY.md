# BUSINESS KNOWLEDGE INVENTORY

Source of truth: authenticated live backend catalog (`qa/description_intelligence/fixtures/catalog-inventory.json`) and verified operational handoff policies (`Python/voice-order/description_intelligence/knowledge.py`).

## Active Services Verified from Backend Catalog

| Service ID | Name | Checklists | Supported Deliverables | Customer-Facing Boundary |
| :--- | :--- | :---: | :--- | :--- |
| `3bd21dde-e2b0-43f0-b32d-ac51941a8e2e` | **Kiểm tra nhà xưởng** | 6 (v3) | `Báo cáo kết quả`, `Ảnh chụp`, `Video` | Mái, kết cấu bên ngoài, vị trí khó tiếp cận; không cam kết kiểm định kết cấu chuyên sâu |
| `8aa5a565-296b-4fbc-9ecb-c8298a811d07` | **Giám sát rừng** | 6 (v3) | `Báo cáo kết quả`, `Ảnh chụp`, `Video` | Hiện trạng rừng, thảm thực vật, điểm bất thường |
| `ad42ddf3-72f4-4c1b-bf45-5a8f40b5bae4` | **Giám sát khu vực** | 5 (v3) | `Ảnh chụp`, `Video` *(NO Báo cáo kết quả)* | Quan sát tổng thể, vị trí cần theo dõi; yêu cầu báo cáo cần đội vận hành xác nhận |
| `b55861a3-c346-4205-b17f-b596f29f6bc2` | **Giám sát công trình** | 2 (v3) | `Báo cáo kết quả`, `Ảnh chụp`, `Video` | Tiến độ, hạng mục chính, hiện trạng thi công |

## Data Routing & Security Boundaries

- **Static verified business rules & service capabilities**: Verified Vietnamese Hybrid RAG (`knowledge.py`), strictly filtered by `serviceId` and `verificationStatus == 'VERIFIED'`.
- **Dynamic prices, active service states, schedules**: Authenticated Spring Boot APIs (`OrderConversationService`).
- **Private customer records**: Authorized canonical session/draft only; never indexed into global RAG.
- **Staff-only boundary**: Flight routes, altitude tuning, drone hardware selection, flight permit approval, pilot assignment.
