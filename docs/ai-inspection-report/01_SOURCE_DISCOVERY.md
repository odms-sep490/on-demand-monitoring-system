# Source discovery

Backend: `on-demand-monitoring-system/ondemandmonitoring` (Spring Boot 4.1.1, Java 21, PostgreSQL). Frontend: `ondemand-monitoring-web` (React 19, Vite 8, Ant Design, Motion). Existing private storage: `media/service/IMediaObjectStorage.java` (`open`, `put`).

## Existing business contracts
- `mission/domain/Mission.java`: Order, schedule, actual timestamps, scope label, status. Multiple photos/videos are MediaAsset records, not report-local uploads.
- `media/domain/MediaAsset.java`: media type, actual S3 bucket/key, size, MIME, checksum, capture timestamp, optional capture/source latitude/longitude, review/storage state.
- `media/repository/MediaAssetRepository.java#findMissionCandidates`: bounded Mission query. `MediaStatus.AVAILABLE` is reviewed; UPLOADING, PENDING_MANAGER_APPROVAL, REJECTED and REMOVED are not eligible.
- `mission/domain/MissionChecklistExecution.java`: inspector assessment/observation, assessedBy/assessedAt. `MissionChecklistEvidence` links actual media, has staff priority (`selectedForCustomer`) and detach history.
- `imagequality/DeliveryImageQualityReader.java`: current checksum/version-aware technical/human decision and perceptual hash. Missing signals are NOT_ANALYZED, not PASS.
- `changeanalysis/ChangeAnalysisController.java`: `ai_change_analysis` records are customer-owned before/after inputs, including optional asset IDs; no reviewed Mission finding registry or guaranteed registration/alignment. Therefore report generation does NOT automatically treat these as inspected defects or invent comparisons.
- `delivery/service/IDeliveryWorkflowService.java`: originalAccessibleMissionIds, publishedMediaAssetIds, requireOriginalAccessForMedia. Existing customer media service additionally checks ownership and retention.
- `mission/service/impl/CustomerMissionReportQueryService.java`: existing published checklist report endpoint `/api/customer/orders/{orderId}/missions/{missionId}/report`; extended UI links the separate versioned report studio without replacing it.
- Existing local Qwen process: `Python/voice-order/description_agent.py` on 8002; shared internal-token boundary; new `/inspection-reports/generate` router uses the same Qwen-Agent/Ollama infrastructure.
- Existing PDFBox 3.0.8 dependency in pom.xml; previously PDF validation only, no photographic inspection renderer.

## Actual catalog
`service/config/ServiceCatalogSeedDataInitializer.java` seeds CONSTRUCTION_MONITORING / Giám sát công trình, FACTORY_INSPECTION / Kiểm tra nhà xưởng, AREA_MONITORING / Giám sát khu vực, FOREST_MONITORING / Giám sát rừng. Reports read current `mission.order.service.name` at runtime; no service IDs are hardcoded.

## Limits
JPEG/PNG only, 12 MiB per source, 30 MP decode limit, maximum 200 Mission media records and 12 automatically selected images. Videos are excluded. No validated defect detector, vision model or reviewed before/after pairing exists in this source contract, so none is claimed. Existing quality signals are reused, not silently recomputed.
