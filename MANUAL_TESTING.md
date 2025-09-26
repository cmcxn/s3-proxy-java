# S3-Compatible API Manual Testing Guide

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

## Start the S3-Compatible API Service
```bash
mvn spring-boot:run
```

## Test Commands

### 1. Upload a file
```bash
echo "Hello, S3-Compatible World!" > test.txt
curl -X PUT --data-binary @test.txt \
  -H "Content-Type: text/plain" \
  "http://localhost:8080/test-bucket/test.txt"
```

### 2. Download the file
```bash
curl "http://localhost:8080/test-bucket/test.txt"
```

### 3. Delete the file
```bash
curl -X DELETE "http://localhost:8080/test-bucket/test.txt"
```

### 4. Check if bucket exists
```bash
curl -I "http://localhost:8080/test-bucket"
```

### 5. Test with binary data
```bash
# Create a small binary file
dd if=/dev/urandom of=binary.dat bs=1024 count=1

# Upload binary file
curl -X PUT --data-binary @binary.dat \
  -H "Content-Type: application/octet-stream" \
  "http://localhost:8080/test-bucket/binary.dat"

# Download and compare
curl "http://localhost:8080/test-bucket/binary.dat" -o downloaded.dat
diff binary.dat downloaded.dat
```

## Expected Results
- All commands should return appropriate HTTP status codes
- File content should be preserved through upload/download cycles
- Binary files should maintain integrity
- S3-compatible endpoints work with standard S3 tools

## Troubleshooting
- Ensure Minio is running and accessible at http://localhost:9000
- Check that the test-bucket exists in Minio
- Verify environment variables are set correctly:
  - MINIO_ENDPOINT=http://localhost:9000
  - MINIO_ACCESS_KEY=minioadmin
  - MINIO_SECRET_KEY=minioadmin

## Using S3 Tools
This service provides S3-compatible endpoints, so you can also use standard S3 tools like aws-cli:

```bash
# Configure AWS CLI to point to your service
aws configure set aws_access_key_id minioadmin
aws configure set aws_secret_access_key minioadmin
aws configure set default.region us-east-1

# Use S3 commands (with --endpoint-url)
aws s3 --endpoint-url http://localhost:8080 ls
aws s3 --endpoint-url http://localhost:8080 cp test.txt s3://test-bucket/
aws s3 --endpoint-url http://localhost:8080 cp s3://test-bucket/test.txt downloaded.txt
```