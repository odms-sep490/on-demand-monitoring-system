CREATE TABLE IF NOT EXISTS order_conversation_sessions (
    id varchar(255) PRIMARY KEY,
    owner_id varchar(255) NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL,
    state jsonb NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz
);
CREATE INDEX IF NOT EXISTS ix_order_conversation_owner_expiry ON order_conversation_sessions(owner_id, expires_at);
