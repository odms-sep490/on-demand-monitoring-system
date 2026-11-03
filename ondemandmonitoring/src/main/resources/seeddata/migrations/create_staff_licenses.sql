-- Staff-issued documentation records are separate from mission flight permits.
CREATE TABLE IF NOT EXISTS staff_licenses (
    id varchar(255) PRIMARY KEY,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone,
    version bigint NOT NULL DEFAULT 0,
    user_id varchar(255) NOT NULL REFERENCES users(id),
    license_type varchar(24) NOT NULL,
    license_number varchar(100) NOT NULL,
    license_name varchar(200) NOT NULL,
    issuing_authority varchar(200) NOT NULL,
    issued_date date NOT NULL,
    expiry_date date,
    license_status varchar(24) NOT NULL,
    verification_status varchar(24) NOT NULL,
    verified_by_id varchar(255) REFERENCES users(id),
    verified_at timestamp without time zone,
    document_storage_path varchar(700),
    document_file_name varchar(255),
    notes varchar(2000),
    CONSTRAINT uk_staff_license_user_number UNIQUE (user_id, license_number)
);
CREATE INDEX IF NOT EXISTS idx_staff_licenses_user ON staff_licenses(user_id);
