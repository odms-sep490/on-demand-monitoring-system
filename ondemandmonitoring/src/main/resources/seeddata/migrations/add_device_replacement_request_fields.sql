-- Store a pilot's pending drone replacement request on the current mission assignment.
ALTER TABLE mission_device_assignments
    ADD COLUMN IF NOT EXISTS replacement_requested_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS replacement_request_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS replacement_requested_by VARCHAR(100);
