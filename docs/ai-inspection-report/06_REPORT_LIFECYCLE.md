# Lifecycle and versioning

DRAFT -> GENERATING (job QUEUED/RUNNING) -> AI_DRAFT (SUCCEEDED), or FAILED. Failed generation can retry. Cancellation discards the stale job result and returns DRAFT. AI_DRAFT/REVISION_REQUESTED may save edits -> AI_DRAFT. Submit -> IN_REVIEW. Manager/Admin may request revision or approve. APPROVED -> FINALIZED by rendering/storing PDF. FINALIZED -> DELIVERED only after existing Mission original-delivery/payment gate permits every included image.

Every mutation has a revision check and row lock. inspection_report_versions snapshots document/action/actor/time. APPROVED, FINALIZED, DELIVERED cannot be edited/regenerated. New report is required for changes. Approved image references are protected against media mutation. Customer ownership, publication and retention are revalidated on each artifact request.

Revision is the mutation/audit clock. The PDF records its finalized snapshot revision; delivery adds an audit revision without regenerating or changing the saved PDF bytes. Reviewed media ID lists are canonicalized before freshness comparisons.
