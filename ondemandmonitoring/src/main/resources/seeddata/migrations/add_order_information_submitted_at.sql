-- Track customer resubmissions independently of the current order status.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS information_submitted_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS ix_orders_information_submitted_at
    ON orders (information_submitted_at DESC) WHERE information_submitted_at IS NOT NULL;
