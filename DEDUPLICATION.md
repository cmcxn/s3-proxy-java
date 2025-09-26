# File Deduplication Feature

This document explains the file deduplication feature added to the S3-proxy-java service.

## Overview

The file deduplication feature implements content-addressable storage with reference counting to eliminate duplicate files and save storage space. When multiple users upload the same file content, only one copy is stored in MinIO, and a reference count tracks how many times the file is used.

## Architecture

### Database Schema

The deduplication system uses two main tables:

#### files Table
- `id` (Primary Key): Unique identifier for each file
- `hash_value` (Unique): SHA-256 hash of the file content
- `size`: File size in bytes
- `content_type`: MIME type of the file
- `storage_path`: Path where the file is stored in MinIO (content-addressed)
- `reference_count`: Number of user mappings referencing this file
- `created_at`: Timestamp when file was first stored
- `updated_at`: Timestamp when reference count was last modified

#### user_files Table
- `id` (Primary Key): Unique identifier for each mapping
- `bucket`: S3 bucket name as provided by client
- `key`: S3 object key as provided by client
- `file_id` (Foreign Key): References files.id
- `created_at`: Timestamp when mapping was created

### Content-Addressable Storage

Files are stored in MinIO using their SHA-256 hash as the object key:
- Storage bucket: `dedupe-storage`
- Storage path: `dedupe-data/{sha256-hash}`

This ensures identical files are stored only once, regardless of their original names or paths.

## Core Operations

### Upload (PutObject)

1. Calculate SHA-256 hash of file content
2. Check if hash already exists in `files` table
3. If exists:
   - Increment reference count
   - Create/update user mapping
4. If new:
   - Store file in MinIO using hash as key
   - Create file record with reference_count=1
   - Create user mapping

### Download (GetObject)

1. Look up user mapping by bucket+key
2. Get file metadata using file_id
3. Retrieve actual file from MinIO using storage_path
4. Return file content with proper headers

### Delete (DeleteObject)

1. Find user mapping by bucket+key
2. Remove user mapping
3. Decrement reference count of associated file
4. If reference count reaches 0:
   - Delete file from MinIO
   - Remove file record from database

## Benefits

- **Space Savings**: Identical files are stored only once
- **Transparent**: No changes required to S3 client applications
- **Atomic Operations**: Reference counting ensures data consistency
- **Performance**: Fast lookups using SHA-256 hash indexing

## Configuration

The deduplication feature uses an embedded H2 database for metadata storage. Database configuration is in `application.properties`:

```properties
# Database Configuration
spring.datasource.url=jdbc:h2:file:./data/s3proxy;AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# JPA Configuration
spring.jpa.hibernate.ddl-auto=update
```

## Monitoring

You can monitor deduplication effectiveness by:

1. Accessing H2 Console at http://localhost:8080/h2-console
2. Viewing database statistics:
   - Total unique files: `SELECT COUNT(*) FROM files`
   - Total user mappings: `SELECT COUNT(*) FROM user_files`
   - Storage savings: Compare total mappings vs unique files

## Limitations

1. **Single Instance**: Current implementation assumes single application instance
2. **Memory Usage**: File content is temporarily loaded into memory during processing
3. **Database**: Uses embedded H2 database (suitable for development/testing)
4. **Storage Backend**: Requires dedicated MinIO bucket for content-addressed storage

## Future Enhancements

- Stream processing for large files to reduce memory usage
- Distributed database support for multi-instance deployments
- Background garbage collection for orphaned files
- Metrics and monitoring endpoints
- Configurable storage backend selection