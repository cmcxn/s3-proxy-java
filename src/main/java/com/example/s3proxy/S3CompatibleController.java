package com.example.s3proxy;

import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.BucketExistsArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

/**
 * S3-compatible controller that handles requests at root level for MinIO SDK compatibility.
 * This controller provides the same functionality as S3ProxyController but without the /proxy prefix,
 * making it compatible with MinIO SDK which doesn't allow paths in endpoints.
 */
@RestController
public class S3CompatibleController {
    private static final Logger log = LoggerFactory.getLogger(S3CompatibleController.class);
    private final MinioClient minio;

    public S3CompatibleController(MinioClient minio) {
        this.minio = minio;
    }

    // HEAD /{bucket} - Check if bucket exists (required by MinIO SDK)
    @RequestMapping(value = "/{bucket}", method = RequestMethod.HEAD)
    public Mono<ResponseEntity<Void>> headBucket(@PathVariable String bucket) {
        return Mono.fromCallable(() -> {
            try {
                // Check if bucket exists using the underlying MinIO client
                boolean exists = minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
                if (exists) {
                    return ResponseEntity.ok().build();
                } else {
                    return ResponseEntity.notFound().build();
                }
            } catch (Exception e) {
                log.error("Error checking bucket existence: ", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        });
    }

    // GET /{bucket} - List objects in bucket (optional, but often expected by SDK)
    @GetMapping(value = "/{bucket}", produces = MediaType.APPLICATION_XML_VALUE)
    public Mono<ResponseEntity<String>> listObjects(@PathVariable String bucket) {
        return Mono.fromCallable(() -> {
            try {
                // For now, return a minimal XML response indicating empty bucket
                // In a full implementation, you'd list objects from MinIO
                String xmlResponse = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Name>%s</Name>
                        <Prefix></Prefix>
                        <Marker></Marker>
                        <MaxKeys>1000</MaxKeys>
                        <IsTruncated>false</IsTruncated>
                    </ListBucketResult>
                    """.formatted(bucket);
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_XML);
                return new ResponseEntity<>(xmlResponse, headers, HttpStatus.OK);
            } catch (Exception e) {
                log.error("Error listing objects: ", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        });
    }

    // GET /{bucket}/{**key} - S3 compatible GET object
    @GetMapping(value = "/{bucket}/{key}")
    public Mono<ResponseEntity<byte[]>> getObject(
            @PathVariable String bucket,
            @PathVariable("key") String key) {
        log.info("GET object: bucket={}, key={}", bucket, key);
        return Mono.fromCallable(() -> {
            try (GetObjectResponse obj = minio.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(key).build())) {
                byte[] data = obj.readAllBytes();
                HttpHeaders h = new HttpHeaders();
                if (obj.headers().get("Content-Type") != null) {
                    h.setContentType(MediaType.parseMediaType(obj.headers().get("Content-Type")));
                }
                return new ResponseEntity<>(data, h, HttpStatus.OK);
            }
        }).onErrorReturn(ResponseEntity.notFound().build());
    }

    // PUT /{bucket}/{**key} - S3 compatible PUT object
    @PutMapping(value = "/{bucket}/{key}")
    public Mono<ResponseEntity<Void>> putObject(
            @PathVariable String bucket,
            @PathVariable("key") String key,
            ServerWebExchange exchange) {
        log.info("PUT object: bucket={}, key={}", bucket, key);
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    try {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);

                        try (InputStream is = new java.io.ByteArrayInputStream(bytes)) {
                            String contentType = exchange.getRequest().getHeaders().getFirst("Content-Type");
                            PutObjectArgs.Builder b = PutObjectArgs.builder()
                                    .bucket(bucket).object(key)
                                    .stream(is, bytes.length, -1);
                            if (contentType != null) b.contentType(contentType);
                            minio.putObject(b.build());
                        }
                        return Mono.just(ResponseEntity.status(HttpStatus.CREATED).<Void>build());
                    } catch (Exception e) {
                        log.error("Error putting object: ", e);
                        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).<Void>build());
                    }
                });
    }

    // DELETE /{bucket}/{**key} - S3 compatible DELETE object
    @DeleteMapping(value = "/{bucket}/{key}")
    public Mono<ResponseEntity<Void>> deleteObject(
            @PathVariable String bucket,
            @PathVariable("key") String key) {
        log.info("DELETE object: bucket={}, key={}", bucket, key);
        return Mono.fromCallable(() -> {
            try {
                minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
                return ResponseEntity.noContent().build();
            } catch (Exception e) {
                log.error("Error deleting object: ", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        });
    }

    // HEAD /{bucket}/{**key} - S3 compatible HEAD object (for stat operations)
    @RequestMapping(value = "/{bucket}/{key}", method = RequestMethod.HEAD)
    public Mono<ResponseEntity<Void>> headObject(
            @PathVariable String bucket,
            @PathVariable("key") String key) {
        log.info("HEAD object: bucket={}, key={}", bucket, key);
        return Mono.fromCallable(() -> {
            try {
                StatObjectResponse stat = minio.statObject(
                        StatObjectArgs.builder().bucket(bucket).object(key).build());
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentLength(stat.size());
                if (stat.contentType() != null) {
                    headers.setContentType(MediaType.parseMediaType(stat.contentType()));
                }
                headers.set("Last-Modified", stat.lastModified().toString());
                headers.set("ETag", stat.etag());
                
                return new ResponseEntity<>(headers, HttpStatus.OK);
            } catch (Exception e) {
                log.debug("Object not found for HEAD request: bucket={}, key={}", bucket, key);
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
        });
    }

    // This endpoint is specifically for presigned URL generation via the proxy endpoint
    // Since MinIO SDK will generate presigned URLs pointing to the direct endpoint,
    // we need a separate mechanism for this
    @GetMapping("/presign/{bucket}/{key}")
    public Mono<Map<String, String>> presignObject(
            @PathVariable String bucket,
            @PathVariable("key") String key,
            @RequestParam(defaultValue = "GET") String method,
            @RequestParam(defaultValue = "600") Integer expirySeconds) {
        log.info("PRESIGN object: bucket={}, key={}, method={}", bucket, key, method);
        return Mono.fromCallable(() -> {
            Method m = switch (method.toUpperCase()) {
                case "PUT" -> Method.PUT;
                case "POST" -> Method.POST;
                case "DELETE" -> Method.DELETE;
                default -> Method.GET;
            };
            String url = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(m).bucket(bucket).object(key).expiry(expirySeconds).build());
            return Map.of("url", url, "method", method);
        });
    }
}