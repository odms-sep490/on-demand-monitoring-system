-- Additive; no order, mission or permit status is modified.
CREATE TABLE IF NOT EXISTS description_sessions (
 id varchar(255) PRIMARY KEY, created_at timestamptz NOT NULL, updated_at timestamptz,
 version bigint NOT NULL DEFAULT 0, owner_id varchar(255) NOT NULL,
 service_id varchar(255) NOT NULL, status varchar(255) NOT NULL,
 revision bigint NOT NULL DEFAULT 0, state jsonb NOT NULL,
 confirmed_at timestamptz, order_id varchar(255)
);
CREATE INDEX IF NOT EXISTS ix_description_sessions_owner ON description_sessions(owner_id);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS customer_flight_purpose_draft varchar(1500);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS description_assistant_session_id varchar(255);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS purpose_customer_confirmed_at timestamptz;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS staff_reviewed_flight_purpose varchar(1500);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS purpose_staff_reviewed_at timestamptz;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS purpose_staff_reviewed_by varchar(255);
