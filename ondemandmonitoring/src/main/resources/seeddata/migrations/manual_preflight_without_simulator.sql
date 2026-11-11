-- Apply once to an existing PostgreSQL database before starting the updated application.
-- Fresh schemas get these nullability/association changes from Hibernate.
ALTER TABLE IF EXISTS persisted_pre_device_check
    ADD COLUMN IF NOT EXISTS mission_device_assignment_id varchar(255);

DO $$
BEGIN
    IF to_regclass('public.persisted_pre_device_check') IS NOT NULL
       AND to_regclass('public.mission_device_assignments') IS NOT NULL
       AND NOT EXISTS (
           SELECT 1 FROM pg_constraint
           WHERE conname = 'fk_persisted_pre_device_check_assignment'
       ) THEN
        ALTER TABLE public.persisted_pre_device_check
            ADD CONSTRAINT fk_persisted_pre_device_check_assignment
            FOREIGN KEY (mission_device_assignment_id)
            REFERENCES public.mission_device_assignments(id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_persisted_pre_device_check_assignment
    ON persisted_pre_device_check (mission_device_assignment_id);

ALTER TABLE IF EXISTS control_handovers
    ALTER COLUMN device_connection_id DROP NOT NULL;
