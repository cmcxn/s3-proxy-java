CREATE TABLE IF NOT EXISTS minio_buckets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE (name)
);

CREATE INDEX IF NOT EXISTS idx_minio_buckets_name ON minio_buckets(name);
CREATE INDEX IF NOT EXISTS idx_minio_buckets_created_at ON minio_buckets(created_at);
