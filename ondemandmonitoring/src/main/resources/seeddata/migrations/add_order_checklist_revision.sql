-- Adds optimistic revision tracking and versioned snapshots for customer order checklist updates.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS checklist_revision BIGINT NOT NULL DEFAULT 0;

UPDATE orders o
SET checklist_revision = 1
WHERE checklist_revision = 0
  AND (checklist_snapshot_at IS NOT NULL
       OR EXISTS (SELECT 1 FROM order_checklist_items i WHERE i.order_id = o.id));

ALTER TABLE order_checklist_items
    ADD COLUMN IF NOT EXISTS checklist_revision BIGINT NOT NULL DEFAULT 1;

UPDATE order_checklist_items i
SET checklist_revision = COALESCE(NULLIF(o.checklist_revision, 0), 1)
FROM orders o
WHERE o.id = i.order_id;

ALTER TABLE order_checklist_items
    DROP CONSTRAINT IF EXISTS uk_order_checklist_source;

ALTER TABLE order_checklist_items
    ADD CONSTRAINT uk_order_checklist_source UNIQUE (order_id, checklist_revision, source_checklist_id);

CREATE INDEX IF NOT EXISTS ix_order_checklist_order_rev
    ON order_checklist_items (order_id, checklist_revision, display_order, id);

CREATE TABLE IF NOT EXISTS order_checklist_revisions (
    id VARCHAR(255) PRIMARY KEY,
    order_id VARCHAR(255) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    actor_id VARCHAR(255) REFERENCES users(id),
    change_summary VARCHAR(2000) NOT NULL,
    payload_fingerprint VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_order_checklist_revision UNIQUE (order_id, revision)
);

CREATE INDEX IF NOT EXISTS ix_order_checklist_revision_order
    ON order_checklist_revisions (order_id, revision);

INSERT INTO order_checklist_revisions (id, order_id, revision, actor_id, change_summary, created_at, updated_at)
SELECT o.id || ':rev:1',
       o.id,
       1,
       o.customer_id,
       'Initial checklist snapshot',
       COALESCE(o.checklist_snapshot_at, o.created_at, NOW()),
       COALESCE(o.checklist_snapshot_at, o.created_at, NOW())
FROM orders o
WHERE o.checklist_revision >= 1
ON CONFLICT (order_id, revision) DO NOTHING;

ALTER TABLE quotes
    ADD COLUMN IF NOT EXISTS checklist_revision BIGINT NOT NULL DEFAULT 1;

UPDATE quotes q
SET checklist_revision = COALESCE(NULLIF(o.checklist_revision, 0), 1)
FROM orders o
WHERE o.id = q.order_id;
