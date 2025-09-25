# Run
# MINIO_ENDPOINT, MINIO_ACCESS_KEY, MINIO_SECRET_KEY can be passed via env or Java system properties
# Example:
# MINIO_ENDPOINT=http://localhost:9000 MINIO_ACCESS_KEY=minioadmin MINIO_SECRET_KEY=minioadmin \
# mvn spring-boot:run
#
# Examples
# Upload: curl -X PUT --data-binary @file.bin "http://127.0.0.1:8080/proxy/mybucket/path/to/file.bin"
# Download: curl -L "http://127.0.0.1:8080/proxy/mybucket/path/to/file.bin" -o file.bin
# Delete: curl -X DELETE "http://127.0.0.1:8080/proxy/mybucket/path/to/file.bin"
# Presign: curl "http://127.0.0.1:8080/proxy/presign/mybucket/path/to/file.bin?method=GET&expiry=600"
#
# Notes
# - For very large files, consider replacing the in-memory buffering with zero-copy streaming using DataBufferUtils#write(...) to a PipedInputStream.
# - If you place this behind NGINX/Ingress, preserve the Host header when you use presigned URLs to avoid signature mismatch.

# Java MinIO / S3 Proxy (Spring Boot WebFlux)


Minimal Java proxy in front of MinIO/S3: GET/PUT/DELETE objects and generate presigned URLs.


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


## Production notes
- Prefer zero-copy streaming for large objects; swap in `DataBufferUtils.write(...)` + `PipedInputStream`.
- If behind Nginx/Ingress, preserve `Host` header for presigned URL flows.
- Add auth (e.g., JWT) and rate limiting before exposure.


## Build jar
```bash
mvn -B -DskipTests package
java -jar target/s3-proxy-java-0.1.0.jar
```


--- .github/workflows/ci.yml ---
name: Java CI
on:
push:
branches: [ main ]
pull_request:
branches: [ main ]
jobs:
build:
runs-on: ubuntu-latest
steps:
- uses: actions/checkout@v4
- name: Set up JDK
uses: actions/setup-java@v4
with:
distribution: temurin
java-version: '17'
cache: 'maven'
- name: Build with Maven
run: mvn -B -DskipTests package