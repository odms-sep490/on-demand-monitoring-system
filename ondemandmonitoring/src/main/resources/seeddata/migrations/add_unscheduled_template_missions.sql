-- Allow missions generated from templates to remain unscheduled until planning.
ALTER TABLE missions
    ALTER COLUMN scheduled_start_at DROP NOT NULL,
    ALTER COLUMN scheduled_end_at DROP NOT NULL;

-- Persist the generating template so repeated deposit callbacks do not create duplicates.
ALTER TABLE missions
    ADD COLUMN mission_template_id varchar(36);

ALTER TABLE missions
    ADD CONSTRAINT uk_mission_order_template UNIQUE (order_id, mission_template_id);
