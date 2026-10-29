ALTER TABLE public.missions
    ADD COLUMN IF NOT EXISTS flight_permit_resource_hold_until timestamptz,
    ADD COLUMN IF NOT EXISTS flight_permit_resources_released_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_reschedule_reason varchar(50),
    ADD COLUMN IF NOT EXISTS last_reschedule_note varchar(1000),
    ADD COLUMN IF NOT EXISTS last_rescheduled_at timestamptz,
    ADD COLUMN IF NOT EXISTS schedule_revision bigint NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS ix_missions_permit_resource_hold
    ON public.missions (flight_permit_status, flight_permit_resource_hold_until);
