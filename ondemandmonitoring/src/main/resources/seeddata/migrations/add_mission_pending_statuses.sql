-- Extend the Mission lifecycle without changing the existing finance statuses.
BEGIN;
DO $$ DECLARE constraint_name text; status_attnum smallint; BEGIN
    SELECT attnum INTO status_attnum FROM pg_attribute
    WHERE attrelid='public.missions'::regclass AND attname='status' AND NOT attisdropped;
    FOR constraint_name IN SELECT conname FROM pg_constraint
        WHERE conrelid='public.missions'::regclass AND contype='c'
          AND conkey=ARRAY[status_attnum] LOOP
        EXECUTE format('ALTER TABLE public.missions DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;
ALTER TABLE public.missions ADD CONSTRAINT ck_missions_status CHECK (status IN (
    'PLANNING','WAITING_DEPOSIT','PENDING_PAYMENT','PENDING_CONFIRMATION','READY','SCHEDULED',
    'CONNECTED','PREFLIGHT_CHECKING','READY_TO_FLY','FAILED_PREFLIGHT',
    'IN_FLIGHT','IN_PROGRESS','RETURNING','POSTFLIGHT_CHECKING','PENDING_REVIEW',
    'COMPLETED','FAILED','CANCELLED'));
COMMIT;
