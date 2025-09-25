# S3 Proxy Manual Testing Guide

## Prerequisites
1. Start a local Minio instance:
```bash
docker run -p 9000:9000 -p 9090:9090 \
  --name minio \
  -v /tmp/data:/data \
  -e "MINIO_ROOT_USER=minioadmin" \
  -e "MINIO_ROOT_PASSWORD=minioadmin" \
  minio/minio server /data --console-address ":9090"
```

2. Create a test bucket:
```bash
# Access Minio Console at http://localhost:9090 (minioadmin/minioadmin)
# Or use mc CLI:
mc alias set myminio http://localhost:9000 minioadmin minioadmin
mc mb myminio/test-bucket
```

## Start the S3 Proxy Service
```bash
mvn spring-boot:run
```

## Test Commands

### 1. Upload a file
```bash
echo "Hello, S3 Proxy World!" > test.txt
curl -X PUT --data-binary @test.txt \
  -H "Content-Type: text/plain" \
  "http://localhost:8080/proxy/test-bucket/test.txt"
```

### 2. Download the file
```bash
curl "http://localhost:8080/proxy/test-bucket/test.txt"
```

### 3. Generate presigned URL
```bash
curl "http://localhost:8080/proxy/presign/test-bucket/test.txt?method=GET&expiry=300"
```

### 4. Delete the file
```bash
curl -X DELETE "http://localhost:8080/proxy/test-bucket/test.txt"
```

### 5. Test with binary data
```bash
# Create a small binary file
dd if=/dev/urandom of=binary.dat bs=1024 count=1

# Upload binary file
curl -X PUT --data-binary @binary.dat \
  -H "Content-Type: application/octet-stream" \
  "http://localhost:8080/proxy/test-bucket/binary.dat"

# Download and compare
curl "http://localhost:8080/proxy/test-bucket/binary.dat" -o downloaded.dat
diff binary.dat downloaded.dat
```

## Expected Results
- All commands should return appropriate HTTP status codes
- File content should be preserved through upload/download cycles
- Presigned URLs should contain valid Minio URLs
- Binary files should maintain integrity

## Troubleshooting
- Ensure Minio is running and accessible at http://localhost:9000
- Check that the test-bucket exists in Minio
- Verify environment variables are set correctly:
  - MINIO_ENDPOINT=http://localhost:9000
  - MINIO_ACCESS_KEY=minioadmin
  - MINIO_SECRET_KEY=minioadmin