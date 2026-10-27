ALTER TABLE IF EXISTS public.missions
    ADD COLUMN IF NOT EXISTS last_reschedule_reason VARCHAR(50),
    ADD COLUMN IF NOT EXISTS last_reschedule_note VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS last_rescheduled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_rescheduled_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS schedule_revision INTEGER NOT NULL DEFAULT 0;
