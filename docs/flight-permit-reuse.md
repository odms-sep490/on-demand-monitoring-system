# Mission-specific flight permits

Supersedes automatic reuse based on Pilot identity. Original, Customer rework and internal supplemental Missions each require their own document uploaded and explicitly approved by Manager, after finalized planning, Staff acceptance and deposit checks.

- Staff acceptance never copies or approves a source Mission permit.
- Changing Pilots still invalidates the approved document and requires a replacement upload.
- `reused_from_mission_id` is retained only for historical compatibility. An inherited permit cannot authorize execution. For a pending/ready Mission, crew reconciliation marks it pending and replacement-required, retaining the old document for reference. Only a Manager upload clears the inherited marker.
- Execution checks independently reject inherited, missing, unapproved, replacement-required or Pilot-mismatched permits, even when reconciliation has not run.
- Completed/in-progress historical records are not rewritten by reconciliation.
- No schema migration or live DB/S3 changes are required. Do not restart a development application configured with `ddl-auto=create` against retained data.
