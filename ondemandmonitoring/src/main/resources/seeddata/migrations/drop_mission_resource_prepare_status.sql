-- Mission resource readiness is now represented by missions.status.
BEGIN;

ALTER TABLE public.missions
    DROP COLUMN IF EXISTS resource_prepare_status;

COMMIT;
