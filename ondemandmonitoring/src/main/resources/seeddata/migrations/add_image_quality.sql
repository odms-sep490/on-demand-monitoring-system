-- Additive, explicitly applied migration. No change to media/delivery states.
CREATE TABLE IF NOT EXISTS image_quality_check (
 id uuid PRIMARY KEY, media_id varchar(255) NOT NULL REFERENCES drone_media(id),
 mission_id varchar(255) NOT NULL, requested_by varchar(255) NOT NULL,
 request_key uuid NOT NULL, customer_request boolean NOT NULL,
 profile varchar(40) NOT NULL, profile_version varchar(40) NOT NULL DEFAULT '1-provisional',
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','PROCESSING','COMPLETED','FAILED','CANCELLED')),
 decision varchar(30) CHECK(decision IN ('PASS','REVIEW_REQUIRED','FAIL','INDETERMINATE')),
 result jsonb, attempts integer NOT NULL DEFAULT 0, lease uuid, lease_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz, completed_at timestamptz,
 error_code varchar(80), UNIQUE(requested_by,request_key,media_id)
);
CREATE INDEX IF NOT EXISTS ix_quality_queue ON image_quality_check(status,created_at);
CREATE INDEX IF NOT EXISTS ix_quality_media ON image_quality_check(media_id,created_at DESC);
CREATE INDEX IF NOT EXISTS ix_quality_mission ON image_quality_check(mission_id,created_at DESC);
CREATE TABLE IF NOT EXISTS image_quality_review (
 id uuid PRIMARY KEY, check_id uuid NOT NULL REFERENCES image_quality_check(id),
 reviewer_id varchar(255) NOT NULL, decision varchar(30) NOT NULL CHECK(decision IN ('PASS','REVIEW_REQUIRED','FAIL')),
 note varchar(2000) NOT NULL DEFAULT '', reviewed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS image_quality_event (
 id bigserial PRIMARY KEY, check_id uuid NOT NULL REFERENCES image_quality_check(id),
 actor varchar(255) NOT NULL, action varchar(40) NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
