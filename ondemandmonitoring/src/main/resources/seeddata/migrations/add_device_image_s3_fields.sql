-- Add S3 object references for the optional device profile image.
-- Presigned URLs are generated per response and are not persisted.
ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS image_s3_key VARCHAR(500),
    ADD COLUMN IF NOT EXISTS image_s3_bucket VARCHAR(255),
    ADD COLUMN IF NOT EXISTS image_url VARCHAR(1000);
