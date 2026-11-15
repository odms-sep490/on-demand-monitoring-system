-- Adds the mission-template scope label when upgrading a database that already
-- contains the initial mission_templates table.
BEGIN;

ALTER TABLE public.mission_templates
    ADD COLUMN IF NOT EXISTS scope_label varchar(255);

UPDATE public.mission_templates
SET scope_label = 'Mission template ' || id
WHERE scope_label IS NULL OR btrim(scope_label) = '';

ALTER TABLE public.mission_templates
    ALTER COLUMN scope_label SET NOT NULL;

COMMIT;
