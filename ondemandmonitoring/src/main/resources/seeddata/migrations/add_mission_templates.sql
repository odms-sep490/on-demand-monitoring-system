-- Adds CRUD-only Mission templates grouped by Service and ChecklistDefinition.
BEGIN;

CREATE TABLE IF NOT EXISTS public.mission_templates (
    id varchar(255) PRIMARY KEY,
    service_id varchar(255) NOT NULL,
    scope_label varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz,
    version bigint NOT NULL,
    CONSTRAINT fk_mission_template_service
        FOREIGN KEY (service_id) REFERENCES public.services(id)
);

CREATE TABLE IF NOT EXISTS public.mission_template_items (
    id varchar(255) PRIMARY KEY,
    mission_template_id varchar(255) NOT NULL,
    checklist_definition_id varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz,
    version bigint NOT NULL,
    CONSTRAINT uk_mission_template_item
        UNIQUE (mission_template_id, checklist_definition_id),
    CONSTRAINT fk_mission_template_item_template
        FOREIGN KEY (mission_template_id) REFERENCES public.mission_templates(id),
    CONSTRAINT fk_mission_template_item_checklist
        FOREIGN KEY (checklist_definition_id) REFERENCES public.checklist_definitions(id)
);

CREATE INDEX IF NOT EXISTS ix_mission_template_service
    ON public.mission_templates(service_id);
CREATE INDEX IF NOT EXISTS ix_mission_template_item_template
    ON public.mission_template_items(mission_template_id);
CREATE INDEX IF NOT EXISTS ix_mission_template_item_checklist
    ON public.mission_template_items(checklist_definition_id);

COMMIT;
