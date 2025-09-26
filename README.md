# Java S3-Compatible API (Spring Boot WebFlux)

S3-compatible API service built with Spring Boot WebFlux that provides direct S3-compatible endpoints for MinIO/S3 storage.

## Features
- ✅ **S3-Compatible API**: Direct S3-compatible endpoints without proxy path prefixes
- ✅ **File Deduplication**: Content-addressable storage with reference counting to eliminate duplicate files
- ✅ **File Operations**: Upload, download, and delete files with preserved content-type headers
- ✅ **Bucket Operations**: Check bucket existence via HEAD requests
- ✅ **Binary Support**: Handle all file types including binary data
- ✅ **Reactive**: Built with Spring WebFlux for non-blocking operations
- ✅ **MinIO SDK Compatible**: Works with MinIO SDK and other S3 clients

## Quick start
```bash
# 1) set env
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin

# 2a) run with H2 database (default - embedded)
mvn spring-boot:run

# 2b) or run with MySQL database
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=yourpassword
mvn spring-boot:run

# 3) try APIs  
curl -X PUT --data-binary @file.bin "http://127.0.0.1:8080/mybucket/path/to/file.bin"
curl -L "http://127.0.0.1:8080/mybucket/path/to/file.bin" -o file.bin
curl -X DELETE "http://127.0.0.1:8080/mybucket/path/to/file.bin"
```

## API Endpoints

The service provides S3-compatible endpoints:

| Method | Endpoint | Description |
|--------|----------|-------------|
| PUT | `/{bucket}/{key}` | Upload file to bucket with given key |
| GET | `/{bucket}/{key}` | Download file from bucket |
| DELETE | `/{bucket}/{key}` | Delete file from bucket |
| HEAD | `/{bucket}` | Check if bucket exists |
| HEAD | `/{bucket}/{key}` | Get object metadata |

### Presigned URL Parameters
- Note: Presigned URL functionality is handled directly by the MinIO client library for S3-compatible access

## Testing

This project includes tests to validate S3-compatible functionality:

### Run All Tests
```bash
mvn test
```

### Test Types
1. **MinIO SDK Compatibility Tests** (`MinioSdkCompatibilityDemoTest`): Tests MinIO SDK integration
2. **MinIO SDK Unit Tests** (`MinioSdkDirectProxyUnitTest`): Unit tests for MinIO SDK operations

### Test Coverage
- ✅ MinIO SDK compatibility
- ✅ S3-compatible API endpoints
- ✅ Error handling and edge cases
- ✅ Real Minio integration via Testcontainers

## Manual Testing
For manual testing with a real Minio instance, see [MANUAL_TESTING.md](MANUAL_TESTING.md).

## File Deduplication
This service includes file deduplication functionality that eliminates duplicate files automatically. See [DEDUPLICATION.md](DEDUPLICATION.md) for detailed documentation on how the deduplication system works.

## Database Configuration
The service supports two database backends:
- **H2** (default): Embedded database suitable for development and testing
- **MySQL**: Production-ready database with optimized indexes

### Using H2 Database (Default)
```bash
# Default configuration - no additional setup required
mvn spring-boot:run
```

### Using MySQL Database
```bash
# 1. Set up MySQL database
CREATE DATABASE s3proxy;

# 2. Configure environment variables
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_USERNAME=your_username
export MYSQL_PASSWORD=your_password

# 3. Run the application
mvn spring-boot:run
```

The application will automatically:
- Create the required tables with optimized indexes
- Set up proper foreign key relationships
- Configure connection pooling for optimal performance

### MySQL Configuration Options
You can customize the MySQL connection by setting these environment variables:
- `MYSQL_USERNAME`: Database username (default: root)
- `MYSQL_PASSWORD`: Database password (default: empty)
- Database URL is configurable in `application-mysql.properties`

## Production notes
- Prefer zero-copy streaming for large objects; swap in `DataBufferUtils.write(...)` + `PipedInputStream`.
- If behind Nginx/Ingress, preserve `Host` header for presigned URL flows.
- Add auth (e.g., JWT) and rate limiting before exposure.

## Build jar
```bash
# Build fat JAR with all dependencies (includes both H2 and MySQL drivers)
mvn -B -DskipTests package

# Run with H2 (default)
java -jar target/s3-proxy-java-0.1.0.jar

# Run with MySQL
SPRING_PROFILES_ACTIVE=mysql MYSQL_USERNAME=root MYSQL_PASSWORD=yourpassword \
java -jar target/s3-proxy-java-0.1.0.jar
```

## Quick Demo
```bash
# Build and run the service
./demo.sh
```

