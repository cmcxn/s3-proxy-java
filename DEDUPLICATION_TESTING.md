# File Deduplication Testing Guide

This document describes how to test the file deduplication feature manually.

## Prerequisites

1. MinIO server running at http://localhost:9000
2. Create a bucket called "dedupe-storage" in MinIO for content-addressed storage
3. Create test bucket (e.g., "test-bucket") for user files

## Setup

```bash
# Start MinIO (if not already running)
# Create the dedupe-storage bucket in MinIO admin interface

# Start the S3-proxy service
cd /home/runner/work/s3-proxy-java/s3-proxy-java
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin
mvn spring-boot:run
```

## Test Deduplication

### 1. Upload the same file content with different names

```bash
# Create test files with identical content
echo "This is test content for deduplication" > file1.txt
cp file1.txt file2.txt
cp file1.txt file3.txt

# Upload the same content with different keys
curl -X PUT --data-binary @file1.txt \
  -H "Content-Type: text/plain" \
  "http://localhost:8080/test-bucket/documents/file1.txt"

curl -X PUT --data-binary @file2.txt \
  -H "Content-Type: text/plain" \
  "http://localhost:8080/test-bucket/documents/file2.txt"

curl -X PUT --data-binary @file3.txt \
  -H "Content-Type: text/plain" \
  "http://localhost:8080/test-bucket/backup/file3.txt"
```

### 2. Verify files can be downloaded individually

```bash
# Download each file and verify content is preserved
curl "http://localhost:8080/test-bucket/documents/file1.txt"
curl "http://localhost:8080/test-bucket/documents/file2.txt"  
curl "http://localhost:8080/test-bucket/backup/file3.txt"

# All should return: "This is test content for deduplication"
```

### 3. Check MinIO storage efficiency

Access MinIO admin interface at http://localhost:9000 and check:
- The `dedupe-storage` bucket should contain only ONE object (the deduplicated file)
- The object name will be the SHA-256 hash of the content
- Multiple user files map to the same physical storage

### 4. Test reference counting with deletion

```bash
# Delete one of the files
curl -X DELETE "http://localhost:8080/test-bucket/documents/file1.txt"

# Verify the other files still work
curl "http://localhost:8080/test-bucket/documents/file2.txt"
curl "http://localhost:8080/test-bucket/backup/file3.txt"

# The physical file should still exist in dedupe-storage

# Delete another file
curl -X DELETE "http://localhost:8080/test-bucket/documents/file2.txt"

# Verify last file still works
curl "http://localhost:8080/test-bucket/backup/file3.txt"

# Delete the last file
curl -X DELETE "http://localhost:8080/test-bucket/backup/file3.txt"

# Now the physical file should be deleted from dedupe-storage
# Verify by checking MinIO admin interface
```

### 5. Verify database state

Access the H2 console at http://localhost:8080/h2-console with:
- JDBC URL: `jdbc:h2:file:./data/s3proxy`
- User: `sa`
- Password: (empty)

Check the tables:
```sql
-- View all files and their reference counts
SELECT * FROM files;

-- View all user file mappings  
SELECT * FROM user_files;

-- Show deduplication effectiveness
SELECT 
  COUNT(*) as total_user_files,
  COUNT(DISTINCT uf.file_id) as unique_physical_files,
  (COUNT(*) - COUNT(DISTINCT uf.file_id)) as space_saved_files
FROM user_files uf;
```

## Expected Results

1. **Storage Efficiency**: Only one physical file stored for identical content
2. **Transparency**: Each user file accessible via its original path
3. **Reference Counting**: Physical file deleted only when all references removed
4. **Data Integrity**: File content preserved through all operations

## Troubleshooting

- Check application logs for deduplication service activity
- Verify dedupe-storage bucket exists in MinIO
- Ensure H2 database tables are created properly
- Check that SHA-256 hashes are calculated correctly