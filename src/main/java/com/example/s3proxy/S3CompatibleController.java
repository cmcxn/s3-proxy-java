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
import io.minio.ListObjectsArgs;
import io.minio.Result;
import io.minio.messages.Item;
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
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * S3-compatible controller that handles requests at root level for MinIO SDK compatibility.
 * This controller provides S3-compatible endpoints without path prefixes,
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
                    HttpHeaders headers = new HttpHeaders();
                    headers.set("x-amz-bucket-region", "us-east-1");
                    headers.set("x-amz-request-id", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
                    headers.set("x-amz-id-2", java.util.UUID.randomUUID().toString());
                    headers.set("Server", "MinIO");
                    return new ResponseEntity<>(headers, HttpStatus.OK);
                } else {
                    return ResponseEntity.notFound().build();
                }
            } catch (Exception e) {
                log.error("Error checking bucket existence: ", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        });
    }

    // GET /{bucket} - List objects in bucket (supports prefix, delimiter, etc.)
    @GetMapping(value = "/{bucket}", produces = MediaType.APPLICATION_XML_VALUE)
    public Mono<ResponseEntity<String>> listObjects(
            @PathVariable String bucket,
            @RequestParam(value = "prefix", required = false, defaultValue = "") String prefix,
            @RequestParam(value = "delimiter", required = false) String delimiter,
            @RequestParam(value = "max-keys", required = false, defaultValue = "1000") Integer maxKeys,
            @RequestParam(value = "marker", required = false, defaultValue = "") String marker) {
        return Mono.fromCallable(() -> {
            try {
                log.info("Listing objects: bucket={}, prefix='{}', delimiter='{}', maxKeys={}", 
                    bucket, prefix, delimiter, maxKeys);
                
                // Build list objects arguments
                ListObjectsArgs.Builder argsBuilder = ListObjectsArgs.builder()
                    .bucket(bucket)
                    .maxKeys(maxKeys);
                
                if (!prefix.isEmpty()) {
                    argsBuilder.prefix(prefix);
                }
                
                if (delimiter != null && !delimiter.isEmpty()) {
                    argsBuilder.delimiter(delimiter);
                }
                
                if (!marker.isEmpty()) {
                    argsBuilder.startAfter(marker);
                }
                
                // Use recursive=true if no delimiter is specified (similar to MinIO Python client behavior)
                boolean recursive = (delimiter == null || delimiter.isEmpty());
                argsBuilder.recursive(recursive);
                
                // Get objects from MinIO
                Iterable<Result<Item>> results = minio.listObjects(argsBuilder.build());
                
                List<Item> items = new ArrayList<>();
                for (Result<Item> result : results) {
                    Item item = result.get();
                    items.add(item);
                    // Stop if we've reached maxKeys
                    if (items.size() >= maxKeys) {
                        break;
                    }
                }
                
                // Build S3-compatible XML response
                StringBuilder xmlBuilder = new StringBuilder();
                xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                xmlBuilder.append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n");
                xmlBuilder.append("  <Name>").append(escapeXml(bucket)).append("</Name>\n");
                xmlBuilder.append("  <Prefix>").append(escapeXml(prefix)).append("</Prefix>\n");
                xmlBuilder.append("  <Marker>").append(escapeXml(marker)).append("</Marker>\n");
                xmlBuilder.append("  <MaxKeys>").append(maxKeys).append("</MaxKeys>\n");
                xmlBuilder.append("  <IsTruncated>").append(items.size() >= maxKeys ? "true" : "false").append("</IsTruncated>\n");
                
                if (delimiter != null && !delimiter.isEmpty()) {
                    xmlBuilder.append("  <Delimiter>").append(escapeXml(delimiter)).append("</Delimiter>\n");
                }
                
                // Add objects to XML
                for (Item item : items) {
                    xmlBuilder.append("  <Contents>\n");
                    xmlBuilder.append("    <Key>").append(escapeXml(item.objectName())).append("</Key>\n");
                    xmlBuilder.append("    <LastModified>").append(item.lastModified().format(DateTimeFormatter.ISO_INSTANT)).append("</LastModified>\n");
                    xmlBuilder.append("    <ETag>").append(escapeXml(item.etag())).append("</ETag>\n");
                    xmlBuilder.append("    <Size>").append(item.size()).append("</Size>\n");
                    xmlBuilder.append("    <StorageClass>STANDARD</StorageClass>\n");
                    xmlBuilder.append("    <Owner>\n");
                    xmlBuilder.append("      <ID>minio</ID>\n");
                    xmlBuilder.append("      <DisplayName>MinIO User</DisplayName>\n");
                    xmlBuilder.append("    </Owner>\n");
                    xmlBuilder.append("  </Contents>\n");
                }
                
                xmlBuilder.append("</ListBucketResult>");
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_XML);
                headers.set("x-amz-request-id", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
                headers.set("x-amz-id-2", java.util.UUID.randomUUID().toString());
                headers.set("Server", "MinIO");
                
                log.info("Successfully listed {} objects for bucket: {}", items.size(), bucket);
                return new ResponseEntity<>(xmlBuilder.toString(), headers, HttpStatus.OK);
            } catch (Exception e) {
                log.error("Error listing objects: ", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        });
    }
    
    // Helper method to escape XML special characters
    private String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
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
                // Add proper S3 headers
                h.set("ETag", obj.headers().get("ETag"));
                h.set("Last-Modified", obj.headers().get("Last-Modified"));
                h.set("x-amz-request-id", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
                h.set("x-amz-id-2", java.util.UUID.randomUUID().toString());
                h.set("Server", "MinIO");
                
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
                        
                        // Return proper S3 response headers
                        HttpHeaders headers = new HttpHeaders();
                        headers.set("ETag", "\"" + Integer.toHexString(java.util.Arrays.hashCode(bytes)) + "\"");
                        headers.set("x-amz-request-id", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
                        headers.set("x-amz-id-2", java.util.UUID.randomUUID().toString());
                        headers.set("Server", "MinIO");
                        
                        return Mono.just(new ResponseEntity<>(headers, HttpStatus.CREATED));
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
                
                // Return proper S3 headers for delete
                HttpHeaders headers = new HttpHeaders();
                headers.set("x-amz-request-id", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
                headers.set("x-amz-id-2", java.util.UUID.randomUUID().toString());
                headers.set("Server", "MinIO");
                
                return new ResponseEntity<>(headers, HttpStatus.NO_CONTENT);
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
                headers.set("Last-Modified", stat.lastModified().format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME));
                headers.set("ETag", stat.etag());
                headers.set("x-amz-request-id", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
                headers.set("x-amz-id-2", java.util.UUID.randomUUID().toString());
                headers.set("Server", "MinIO");
                
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