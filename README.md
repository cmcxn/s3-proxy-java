# Java MinIO / S3 Proxy (Spring Boot WebFlux)

Minimal Java proxy in front of MinIO/S3: GET/PUT/DELETE objects and generate presigned URLs.

## Features
- ✅ **File Upload (PUT)**: Upload files with automatic content-type detection
- ✅ **File Download (GET)**: Download files with preserved content-type headers
- ✅ **File Delete (DELETE)**: Remove files from storage
- ✅ **Presigned URLs**: Generate secure temporary URLs for direct client access
- ✅ **Binary Support**: Handle all file types including binary data
- ✅ **Reactive**: Built with Spring WebFlux for non-blocking operations

## Quick start
```bash
# 1) set env
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin

# 2) run
mvn spring-boot:run

# 3) try APIs
curl -X PUT --data-binary @file.bin "http://127.0.0.1:8080/proxy/mybucket/path/to/file.bin"
curl -L "http://127.0.0.1:8080/proxy/mybucket/path/to/file.bin" -o file.bin
curl -X DELETE "http://127.0.0.1:8080/proxy/mybucket/path/to/file.bin"
curl "http://127.0.0.1:8080/proxy/presign/mybucket/path/to/file.bin?method=GET&expiry=600"
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| PUT | `/proxy/{bucket}/{key}` | Upload file to bucket with given key |
| GET | `/proxy/{bucket}/{key}` | Download file from bucket |
| DELETE | `/proxy/{bucket}/{key}` | Delete file from bucket |
| GET | `/proxy/presign/{bucket}/{key}` | Generate presigned URL |

### Presigned URL Parameters
- `method`: HTTP method (GET, PUT, DELETE) - default: GET
- `expiry`: URL expiration time in seconds - default: 600

## Testing

This project includes comprehensive tests to validate all S3 proxy functionality:

### Run All Tests
```bash
mvn test
```

### Test Types
1. **Integration Tests** (`S3ProxyIntegrationTest`): Full end-to-end testing with real Minio containers
2. **HTTP Client Tests** (`HttpClientProxyTest`): Simulates real-world usage with RestTemplate  
3. **Simple Tests** (`S3ProxySimpleTest`): Basic file operations validation

### Test Coverage
- ✅ File upload/download with various content types
- ✅ Binary file handling
- ✅ Large file operations (1MB+)
- ✅ Presigned URL generation
- ✅ Error handling and edge cases
- ✅ Real Minio integration via Testcontainers

## Manual Testing
For manual testing with a real Minio instance, see [MANUAL_TESTING.md](MANUAL_TESTING.md).

## Production notes
- Prefer zero-copy streaming for large objects; swap in `DataBufferUtils.write(...)` + `PipedInputStream`.
- If behind Nginx/Ingress, preserve `Host` header for presigned URL flows.
- Add auth (e.g., JWT) and rate limiting before exposure.

## Build jar
```bash
mvn -B -DskipTests package
java -jar target/s3-proxy-java-0.1.0.jar
```

## Quick Demo
```bash
# Build and run the service
./demo.sh
```

