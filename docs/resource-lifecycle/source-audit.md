# Source findings
DeviceModel already owns manufacturer/modelName/specsMetadata and catalog update endpoints. DeviceTelemetry currently stores assignment-linked position, battery percent, connected and ingestion timestamp only. No per-pack battery entity exists: SOH/temperature/voltage and per-pack cycle metrics must remain unavailable until actual integration supplies them.
MaintenanceTicketService currently resolves and immediately restores AVAILABLE; requires a verified post-maintenance gate. MaintenanceAuthorizationService permits only assigned STAFF to resolve; manager/admin manage ticket assignment.
MissionService owns assignment/replacement and reserves through ResourceReservationServiceImpl. Central enforcement belongs in reservation revalidation plus role-aware assignment; do not build a parallel assign API.
Usage must clip to actual assignment/Mission intervals; work schedules are not measured working time. Existing Mission and assignment records provide estimated interval fallback only. Historical lifetime totals without initial baseline cannot be represented as complete.
Existing AI Ops has model_type strings, execution audits and runtime status. Register rule/optimization components truthfully, no model score fabrication.
Concurrent user feedback changes in FE/BE present at task start and preserved.
