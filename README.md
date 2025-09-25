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

