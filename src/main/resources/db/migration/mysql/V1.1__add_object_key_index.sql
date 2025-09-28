-- MySQL-specific: Add index for object_key with prefix length
CREATE INDEX idx_minio_user_files_object_key
    ON minio_user_files (object_key(255));
