-- V2__Add_metadata_columns.sql
-- Add metadata storage and last_modified timestamp for user files to track S3 metadata

ALTER TABLE minio_user_files
    ADD COLUMN metadata_json TEXT NULL AFTER created_at,
    ADD COLUMN last_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER metadata_json;

-- Backfill last_modified with the existing created_at values
UPDATE minio_user_files SET last_modified = created_at WHERE last_modified IS NULL;
