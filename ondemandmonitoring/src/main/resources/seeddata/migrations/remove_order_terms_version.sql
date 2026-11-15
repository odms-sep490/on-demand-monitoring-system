-- Remove the retired terms-version field from an existing database.
ALTER TABLE orders DROP COLUMN IF EXISTS terms_version;
