CREATE TABLE IF NOT EXISTS inspection_reports (
 id uuid PRIMARY KEY, mission_id varchar(255) NOT NULL REFERENCES missions(id),
 status varchar(32) NOT NULL, revision bigint NOT NULL DEFAULT 0,
 document jsonb, evidence jsonb NOT NULL DEFAULT '[]',
 created_by varchar(255) NOT NULL, approved_by varchar(255), approved_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 job_id uuid, job_stage varchar(64), error text, model varchar(200),
 pdf_bucket text, pdf_key text, delivered_at timestamptz
);
CREATE INDEX IF NOT EXISTS ix_inspection_reports_mission ON inspection_reports(mission_id,created_at DESC);
ALTER TABLE inspection_reports ADD COLUMN IF NOT EXISTS job_status varchar(30);
CREATE TABLE IF NOT EXISTS inspection_report_versions (
 report_id uuid NOT NULL REFERENCES inspection_reports(id), revision bigint NOT NULL,
 action varchar(40) NOT NULL, actor varchar(255) NOT NULL, document jsonb,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(report_id,revision)
);
