-- Adds the independent ChecklistDefinition <-> DeviceType association.
BEGIN;

CREATE TABLE IF NOT EXISTS public.checklist_device_requirements (
    id varchar(255) PRIMARY KEY,
    checklist_definition_id varchar(255) NOT NULL,
    device_type_id varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz,
    version bigint NOT NULL,
    CONSTRAINT uk_checklist_device_requirement
        UNIQUE (checklist_definition_id, device_type_id),
    CONSTRAINT fk_checklist_device_requirement_checklist
        FOREIGN KEY (checklist_definition_id)
        REFERENCES public.checklist_definitions(id),
    CONSTRAINT fk_checklist_device_requirement_type
        FOREIGN KEY (device_type_id)
        REFERENCES public.device_types(id)
);

CREATE INDEX IF NOT EXISTS ix_checklist_device_requirement_checklist
    ON public.checklist_device_requirements(checklist_definition_id);
CREATE INDEX IF NOT EXISTS ix_checklist_device_requirement_type
    ON public.checklist_device_requirements(device_type_id);

COMMIT;
