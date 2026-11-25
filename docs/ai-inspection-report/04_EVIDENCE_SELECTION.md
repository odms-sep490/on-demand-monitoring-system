# Explainable evidence selection

ReportEvidenceService retrieves only Mission candidates, then filters reviewed AVAILABLE image records and AVAILABLE storage. MIME, actual decoder format, byte size, pixel limit and decoded dimensions are checked on actual private bytes. Failed reads produce explicit warnings; no readable eligible images blocks generation.

Score = PASS 30 + min(20, megapixels*5) + linked reviewed finding 40 + staff-selected priority 10 - near-duplicate penalty 10 - FAIL-quality penalty 30. It is a ranking score, NOT probability/confidence. Stable media ID breaks ties. Exact checksum duplicates may be excluded, but distinct finding-linked or priority evidence is retained. Perceptual similarity lowers ranking and never alone deletes evidence. Low quality is visible to human review; NOT_ANALYZED is explicit.

Automatically take top 12. Images and finding links are persisted by IDs, never signed URLs. Staff can replace/add/remove/reorder and edit captions. Overview is a document representative role, not an assertion that a text model classified scene pixels. No close-up or before/after label is fabricated. Original images remain immutable. Report references also prevent orphan cleanup/deletion of source evidence through MissionMediaEvidenceGuard.
