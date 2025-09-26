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

The deduplication feature supports two database backends:

### H2 Database (Default)
Uses an embedded H2 database for metadata storage. Database configuration is in `application.properties`:

```properties
# H2 Database Configuration (default)
spring.datasource.url=jdbc:h2:file:./data/s3proxy;AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# JPA Configuration
spring.jpa.hibernate.ddl-auto=update
```

### MySQL Database (Production)
For production deployments, you can use MySQL with optimized indexes and connection pooling:

```bash
# Enable MySQL profile
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_USERNAME=your_username
export MYSQL_PASSWORD=your_password
```

MySQL configuration is in `application-mysql.properties` with optimized settings:

```properties
# MySQL Database Configuration
spring.datasource.url=jdbc:mysql://localhost:3306/s3proxy?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC
spring.datasource.driverClassName=com.mysql.cj.jdbc.Driver

# Connection pooling for optimal performance
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5

# JPA Configuration for MySQL
spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect
spring.jpa.hibernate.ddl-auto=update
```

#### MySQL Optimized Indexes
The MySQL configuration includes optimized indexes for all query patterns:

**Files Table:**
- `idx_files_hash_value` - Unique hash lookups (primary deduplication operation)
- `idx_files_reference_count` - Cleanup operations for unreferenced files
- `idx_files_created_at` - Time-based queries and analytics
- `idx_files_size` - Size-based filtering and statistics

**User Files Table:**
- `idx_user_files_bucket` - Fast bucket-level operations
- `idx_user_files_object_key` - Quick object key lookups
- `idx_user_files_bucket_key` - Combined queries (most common access pattern)
- `idx_user_files_file_id` - Foreign key performance for joins
- `idx_user_files_created_at` - Time-based analytics

See [MYSQL_CONFIGURATION.md](MYSQL_CONFIGURATION.md) for detailed MySQL setup instructions.

## Monitoring

You can monitor deduplication effectiveness by connecting to your database:

### H2 Database (Default)
1. Access H2 Console at http://localhost:8080/h2-console (when running with H2)
2. Use JDBC URL: `jdbc:h2:file:./data/s3proxy`

### MySQL Database
1. Connect using MySQL client: `mysql -u username -p s3proxy`
2. Or use any MySQL GUI tool (MySQL Workbench, phpMyAdmin, etc.)

### Common Monitoring Queries

```sql
-- Total unique files stored
SELECT COUNT(*) as unique_files FROM files;

-- Total user file mappings
SELECT COUNT(*) as total_mappings FROM user_files;

-- Storage efficiency (higher ratio = better deduplication)
SELECT 
    (SELECT COUNT(*) FROM user_files) as total_mappings,
    (SELECT COUNT(*) FROM files) as unique_files,
    ROUND((SELECT COUNT(*) FROM user_files) / (SELECT COUNT(*) FROM files), 2) as efficiency_ratio;

-- Top buckets by file count
SELECT bucket, COUNT(*) as file_count 
FROM user_files 
GROUP BY bucket 
ORDER BY file_count DESC 
LIMIT 10;

-- Files by size distribution
SELECT 
    CASE 
        WHEN size < 1024 THEN 'Under 1KB'
        WHEN size < 1048576 THEN '1KB-1MB'
        WHEN size < 104857600 THEN '1MB-100MB'
        ELSE 'Over 100MB'
    END as size_category,
    COUNT(*) as file_count,
    SUM(size) as total_bytes
FROM files 
GROUP BY size_category;
```

## Limitations

1. **Memory Usage**: File content is temporarily loaded into memory during processing
2. **Storage Backend**: Requires dedicated MinIO bucket for content-addressed storage
3. **Database Choice**: 
   - **H2**: Single application instance, suitable for development/testing
   - **MySQL**: Production-ready, supports multiple instances with proper setup

## Future Enhancements

- Stream processing for large files to reduce memory usage
- Distributed database support for multi-instance deployments
- Background garbage collection for orphaned files
- Metrics and monitoring endpoints
- Configurable storage backend selection