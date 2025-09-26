# MySQL Database Configuration

This document provides detailed information about configuring and using MySQL as the database backend for S3-proxy-java.

## Overview

The S3-proxy-java service supports MySQL as an alternative to the default H2 embedded database. MySQL provides:

- **Production scalability**: Handle high-volume workloads
- **Concurrent access**: Multiple application instances
- **Optimized performance**: Custom indexes for S3 operations
- **Data persistence**: Reliable storage for file metadata
- **ACID compliance**: Transactional consistency

## Prerequisites

1. **MySQL Server** 8.0 or later
2. **Java 17** or later
3. **Network connectivity** to MySQL server

## Quick Setup

### 1. Create Database
```sql
CREATE DATABASE s3proxy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Optional: Create dedicated user
CREATE USER 's3proxy'@'%' IDENTIFIED BY 'your_secure_password';
GRANT ALL PRIVILEGES ON s3proxy.* TO 's3proxy'@'%';
FLUSH PRIVILEGES;
```

### 2. Configure Environment Variables
```bash
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_USERNAME=s3proxy
export MYSQL_PASSWORD=your_secure_password
```

### 3. Run Application
```bash
# Using Maven
mvn spring-boot:run

# Using JAR
java -jar target/s3-proxy-java-0.1.0.jar
```

## Advanced Configuration

### Database URL Customization
Edit `src/main/resources/application-mysql.properties`:

```properties
# Custom MySQL connection
spring.datasource.url=jdbc:mysql://mysql-host:3306/s3proxy?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC

# Connection pool settings
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.idle-timeout=300000
```

### Performance Tuning
The application creates optimized indexes automatically:

#### Files Table Indexes:
- `idx_files_hash_value` - Fast hash lookups for deduplication
- `idx_files_reference_count` - Efficient cleanup of unreferenced files
- `idx_files_created_at` - Time-based queries
- `idx_files_size` - Size-based filtering

#### User Files Table Indexes:
- `idx_user_files_bucket` - Fast bucket operations
- `idx_user_files_object_key` - Quick object lookups
- `idx_user_files_bucket_key` - Combined bucket+key queries (most common)
- `idx_user_files_file_id` - Foreign key performance
- `idx_user_files_created_at` - Time-based queries

## Schema Management

### Automatic Table Creation
The application automatically creates tables and indexes on startup when using the MySQL profile. The schema is defined in:
- `src/main/resources/db/migration/mysql-schema.sql`

### Manual Schema Creation
If you prefer manual control:

```sql
-- See the complete schema in:
-- src/main/resources/db/migration/mysql-schema.sql
```

## Monitoring and Maintenance

### Database Statistics
Monitor the deduplication effectiveness:

```sql
-- Total unique files
SELECT COUNT(*) as unique_files FROM files;

-- Total file mappings
SELECT COUNT(*) as total_mappings FROM user_files;

-- Storage efficiency ratio
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
```

### Maintenance Operations

```sql
-- Find orphaned files (reference_count = 0)
SELECT * FROM files WHERE reference_count = 0;

-- Cleanup orphaned files (use with caution)
DELETE FROM files WHERE reference_count = 0;

-- Rebuild table statistics
ANALYZE TABLE files, user_files;
```

### Performance Monitoring

Check query performance:

```sql
-- Monitor slow queries
SHOW VARIABLES LIKE 'slow_query_log';
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 1;

-- Check index usage
SHOW INDEX FROM files;
SHOW INDEX FROM user_files;
```

## Troubleshooting

### Common Issues

#### Connection Refused
```
Error: Connection refused to MySQL server
```
**Solutions:**
1. Verify MySQL server is running
2. Check firewall settings
3. Verify connection parameters

#### Table Already Exists
```
Error: Table 'files' already exists
```
**Solutions:**
1. This is expected behavior - the application handles existing tables
2. Logs will show warnings but continue normally

#### Index Creation Errors
```
Error: Duplicate key name 'idx_files_hash_value'
```
**Solutions:**
1. This is expected when restarting the application
2. Indexes are created with IF NOT EXISTS when possible

### Performance Issues

#### Slow Queries
1. **Check index usage:**
   ```sql
   EXPLAIN SELECT * FROM user_files WHERE bucket = 'mybucket' AND object_key = 'mykey';
   ```

2. **Verify index statistics:**
   ```sql
   SHOW INDEX FROM user_files;
   ```

3. **Update table statistics:**
   ```sql
   ANALYZE TABLE files, user_files;
   ```

### Migration from H2

To migrate existing H2 data to MySQL:

1. **Export H2 data:**
   ```bash
   # Connect to H2 console and export data
   # http://localhost:8080/h2-console
   ```

2. **Transform and import to MySQL:**
   ```sql
   -- Adjust data types and import
   -- Consider data transformation scripts
   ```

## Security Considerations

1. **Use dedicated database user** with minimal privileges
2. **Enable SSL** for database connections
3. **Configure firewall** to restrict database access
4. **Use strong passwords** and consider password rotation
5. **Regular backups** of the database

### SSL Configuration
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/s3proxy?useSSL=true&requireSSL=true&serverTimezone=UTC
```

## Backup and Recovery

### Backup Strategy
```bash
# Create backup
mysqldump -u s3proxy -p s3proxy > s3proxy_backup_$(date +%Y%m%d_%H%M%S).sql

# Automated backup script
#!/bin/bash
BACKUP_DIR="/backups/s3proxy"
DATE=$(date +%Y%m%d_%H%M%S)
mysqldump -u s3proxy -p s3proxy | gzip > "$BACKUP_DIR/s3proxy_$DATE.sql.gz"
```

### Recovery
```bash
# Restore from backup
mysql -u s3proxy -p s3proxy < s3proxy_backup.sql
```

## Production Deployment

### Recommended MySQL Configuration
```ini
# /etc/mysql/mysql.conf.d/mysqld.cnf
[mysqld]
# Performance
innodb_buffer_pool_size = 2G
innodb_log_file_size = 512M
innodb_flush_log_at_trx_commit = 2
innodb_file_per_table = 1

# Character set
character-set-server = utf8mb4
collation-server = utf8mb4_unicode_ci

# Connection limits
max_connections = 200
max_connect_errors = 10000

# Logging
slow_query_log = 1
slow_query_log_file = /var/log/mysql/mysql-slow.log
long_query_time = 1
```

### Application Configuration
```properties
# Production MySQL settings
spring.datasource.hikari.maximum-pool-size=50
spring.datasource.hikari.minimum-idle=10
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.validation-timeout=5000
spring.datasource.hikari.leak-detection-threshold=60000
```

## Comparison: H2 vs MySQL

| Feature | H2 | MySQL |
|---------|-------|-------|
| **Setup** | Zero configuration | Requires MySQL server |
| **Performance** | Fast (in-memory) | Excellent (optimized for production) |
| **Scalability** | Single instance | Multi-instance ready |
| **Persistence** | File-based | Network database |
| **Production** | Development/testing | Production ready |
| **Monitoring** | H2 Console | Full MySQL tooling |
| **Backup** | File copy | mysqldump + replication |

Choose MySQL for:
- Production deployments
- Multi-instance setups
- High availability requirements
- Advanced monitoring needs

Choose H2 for:
- Development and testing
- Proof of concepts
- Single-instance deployments
- Minimal setup requirements