-- MySQL schema initialization for S3 Proxy deduplication
-- This file is used when running with MySQL profile

CREATE TABLE IF NOT EXISTS files (
    id BIGINT NOT NULL AUTO_INCREMENT,
    hash_value VARCHAR(64) NOT NULL UNIQUE,
    size BIGINT NOT NULL,
    content_type VARCHAR(255),
    storage_path VARCHAR(500) NOT NULL,
    reference_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Optimized indexes for files table
CREATE INDEX IF NOT EXISTS idx_files_hash_value ON files(hash_value);
CREATE INDEX IF NOT EXISTS idx_files_reference_count ON files(reference_count);
CREATE INDEX IF NOT EXISTS idx_files_created_at ON files(created_at);
CREATE INDEX IF NOT EXISTS idx_files_size ON files(size);

CREATE TABLE IF NOT EXISTS user_files (
    id BIGINT NOT NULL AUTO_INCREMENT,
    bucket VARCHAR(255) NOT NULL,
    object_key VARCHAR(1000) NOT NULL,
    file_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket_key (bucket, object_key(255)),
    FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Optimized indexes for user_files table
CREATE INDEX IF NOT EXISTS idx_user_files_bucket ON user_files(bucket);
CREATE INDEX IF NOT EXISTS idx_user_files_object_key ON user_files(object_key(255));
CREATE INDEX IF NOT EXISTS idx_user_files_bucket_key ON user_files(bucket, object_key(255));
CREATE INDEX IF NOT EXISTS idx_user_files_file_id ON user_files(file_id);
CREATE INDEX IF NOT EXISTS idx_user_files_created_at ON user_files(created_at);

-- Performance optimization: Analyze tables after creation
ANALYZE TABLE files;
ANALYZE TABLE user_files;