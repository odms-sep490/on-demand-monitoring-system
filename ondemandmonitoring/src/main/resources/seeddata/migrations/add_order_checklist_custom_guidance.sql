-- Additive, rerunnable foundation only. Historical catalog snapshots and quotes stay unchanged.
ALTER TABLE order_checklist_items
    ADD COLUMN IF NOT EXISTS custom_guidance JSONB,
    ADD COLUMN IF NOT EXISTS guidance_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS guidance_confirmed_version BIGINT,
    ADD COLUMN IF NOT EXISTS guidance_updated_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS guidance_updated_at TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_order_checklist_guidance_updated_by'
          AND conrelid = 'order_checklist_items'::regclass
    ) THEN
        ALTER TABLE order_checklist_items
            ADD CONSTRAINT fk_order_checklist_guidance_updated_by
            FOREIGN KEY (guidance_updated_by) REFERENCES users(id);
    END IF;
END $$;

ALTER TABLE quotes
    ADD COLUMN IF NOT EXISTS checklist_guidance_snapshot JSONB;
