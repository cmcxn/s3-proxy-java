-- V1__Create_initial_schema.sql
-- Initial schema for S3 Proxy deduplication with updated table names and SHA256 support

-- Create minio_files table (renamed from files)
CREATE TABLE minio_files (
    id BIGINT NOT NULL AUTO_INCREMENT,
    hash_value VARCHAR(64) NOT NULL UNIQUE,
    size BIGINT NOT NULL,
    content_type VARCHAR(255),
    storage_path VARCHAR(500) NOT NULL,
    reference_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_minio_files_hash_value (hash_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Optimized indexes for minio_files table
CREATE INDEX idx_minio_files_reference_count ON minio_files(reference_count);
CREATE INDEX idx_minio_files_created_at ON minio_files(created_at);
CREATE INDEX idx_minio_files_size ON minio_files(size);

-- Create minio_user_files table (renamed from user_files) with SHA256 support
CREATE TABLE minio_user_files (
    id BIGINT NOT NULL AUTO_INCREMENT,
    bucket VARCHAR(255) NOT NULL,
    object_key TEXT NOT NULL,
    object_key_sha256 VARCHAR(64) NOT NULL,
    file_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket_key_sha256 (bucket, object_key_sha256),
    FOREIGN KEY (file_id) REFERENCES minio_files(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Optimized indexes for minio_user_files table
CREATE INDEX idx_minio_user_files_bucket ON minio_user_files(bucket);
CREATE INDEX idx_minio_user_files_object_key_sha256 ON minio_user_files(object_key_sha256);
CREATE INDEX idx_minio_user_files_bucket_key_sha256 ON minio_user_files(bucket, object_key_sha256);
CREATE INDEX idx_minio_user_files_file_id ON minio_user_files(file_id);
CREATE INDEX idx_minio_user_files_created_at ON minio_user_files(created_at);

-- Performance optimization: Analyze tables after creation
ANALYZE TABLE minio_files;
ANALYZE TABLE minio_user_files;