-- V2__Add_metadata_columns.sql
-- Add metadata storage and last_modified timestamp for user files in H2 schema

ALTER TABLE minio_user_files
    ADD COLUMN metadata_json CLOB NULL;

ALTER TABLE minio_user_files
    ADD COLUMN last_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE minio_user_files SET last_modified = created_at WHERE last_modified IS NULL;
