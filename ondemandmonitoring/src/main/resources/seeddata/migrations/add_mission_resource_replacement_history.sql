ALTER TABLE public.mission_device_assignments
    ADD COLUMN IF NOT EXISTS replaced_by_assignment_id varchar(255);

ALTER TABLE public.mission_staff_assignments
    ADD COLUMN IF NOT EXISTS replaced_by_assignment_id varchar(255);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_mission_device_assignment_replaced_by'
          AND conrelid = 'public.mission_device_assignments'::regclass
    ) THEN
        ALTER TABLE public.mission_device_assignments
            ADD CONSTRAINT fk_mission_device_assignment_replaced_by
            FOREIGN KEY (replaced_by_assignment_id)
            REFERENCES public.mission_device_assignments(id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_mission_staff_assignment_replaced_by'
          AND conrelid = 'public.mission_staff_assignments'::regclass
    ) THEN
        ALTER TABLE public.mission_staff_assignments
            ADD CONSTRAINT fk_mission_staff_assignment_replaced_by
            FOREIGN KEY (replaced_by_assignment_id)
            REFERENCES public.mission_staff_assignments(id)
            ON DELETE RESTRICT;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_mission_device_assignment_replacement_target
    ON public.mission_device_assignments (replaced_by_assignment_id)
    WHERE replaced_by_assignment_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_mission_staff_assignment_replacement_target
    ON public.mission_staff_assignments (replaced_by_assignment_id)
    WHERE replaced_by_assignment_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_mission_device_assignment_replacement_history
    ON public.mission_device_assignments (mission_id, released_at)
    WHERE replaced_by_assignment_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_mission_staff_assignment_replacement_history
    ON public.mission_staff_assignments (mission_id, released_at)
    WHERE replaced_by_assignment_id IS NOT NULL;
