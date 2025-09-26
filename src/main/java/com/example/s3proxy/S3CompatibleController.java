package com.example.s3proxy;

import com.example.s3proxy.service.DeduplicationService;
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
    private final DeduplicationService deduplicationService;

    public S3CompatibleController(MinioClient minio, DeduplicationService deduplicationService) {
        this.minio = minio;
        this.deduplicationService = deduplicationService;
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
                List<String> commonPrefixes = new ArrayList<>();
                boolean isTruncated = false;
                String nextMarker = null;
                
                for (Result<Item> result : results) {
                    Item item = result.get();
                    
                    // When using delimiter, handle common prefixes for directory-style listing
                    if (delimiter != null && !delimiter.isEmpty()) {
                        String objectName = item.objectName();
                        // Remove the prefix from the object name
                        String relativeObjectName = objectName;
                        if (!prefix.isEmpty() && objectName.startsWith(prefix)) {
                            relativeObjectName = objectName.substring(prefix.length());
                        }
                        
                        // Check if this is a "directory" (contains delimiter after prefix)
                        int delimiterIndex = relativeObjectName.indexOf(delimiter);
                        if (delimiterIndex > 0) {
                            // This is a common prefix (directory)
                            String commonPrefix = prefix + relativeObjectName.substring(0, delimiterIndex + delimiter.length());
                            if (!commonPrefixes.contains(commonPrefix)) {
                                commonPrefixes.add(commonPrefix);
                            }
                            // Don't add to items if it's just a directory marker
                            continue;
                        }
                    }
                    
                    items.add(item);
                    // Stop if we've reached maxKeys
                    if (items.size() >= maxKeys) {
                        isTruncated = true;
                        nextMarker = item.objectName(); // Set next marker to the last object processed
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
                xmlBuilder.append("  <IsTruncated>").append(isTruncated ? "true" : "false").append("</IsTruncated>\n");
                
                if (delimiter != null && !delimiter.isEmpty()) {
                    xmlBuilder.append("  <Delimiter>").append(escapeXml(delimiter)).append("</Delimiter>\n");
                }
                
                // Add NextMarker when results are truncated
                if (isTruncated && nextMarker != null) {
                    xmlBuilder.append("  <NextMarker>").append(escapeXml(nextMarker)).append("</NextMarker>\n");
                }
                
                // Add objects to XML
                for (Item item : items) {
                    xmlBuilder.append("  <Contents>\n");
                    xmlBuilder.append("    <Key>").append(escapeXml(item.objectName())).append("</Key>\n");
                    // Format LastModified to match MinIO's format with milliseconds
                    String lastModified = item.lastModified().format(DateTimeFormatter.ISO_INSTANT);
                    if (!lastModified.contains(".")) {
                        // Add .000 if there are no milliseconds
                        lastModified = lastModified.replace("Z", ".000Z");
                    }
                    xmlBuilder.append("    <LastModified>").append(lastModified).append("</LastModified>\n");
                    // ETag should include quotes and not be XML escaped
                    String etag = item.etag();
                    if (!etag.startsWith("\"")) {
                        etag = "\"" + etag + "\"";
                    }
                    xmlBuilder.append("    <ETag>").append(etag).append("</ETag>\n");
                    xmlBuilder.append("    <Size>").append(item.size()).append("</Size>\n");
                    xmlBuilder.append("    <StorageClass>STANDARD</StorageClass>\n");
                    xmlBuilder.append("    <Owner>\n");
                    xmlBuilder.append("      <ID>minio</ID>\n");
                    xmlBuilder.append("      <DisplayName>MinIO User</DisplayName>\n");
                    xmlBuilder.append("    </Owner>\n");
                    xmlBuilder.append("  </Contents>\n");
                }
                
                // Add common prefixes for directory-style listing
                for (String commonPrefix : commonPrefixes) {
                    xmlBuilder.append("  <CommonPrefixes>\n");
                    xmlBuilder.append("    <Prefix>").append(escapeXml(commonPrefix)).append("</Prefix>\n");
                    xmlBuilder.append("  </CommonPrefixes>\n");
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
    @GetMapping(value = "/{bucket}/**")
    public Mono<ResponseEntity<byte[]>> getObject(
            @PathVariable String bucket,
            ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        // Extract key by removing the bucket part: /bucket/key -> key
        String key = path.substring(("/" + bucket + "/").length());
        log.info("GET object: bucket={}, key={}", bucket, key);
        
        return Mono.fromCallable(() -> {
            try {
                DeduplicationService.FileData fileData = deduplicationService.getObject(bucket, key);
                if (fileData == null) {
                    return ResponseEntity.notFound().build();
                }
                
                HttpHeaders h = new HttpHeaders();
                if (fileData.getContentType() != null) {
                    h.setContentType(MediaType.parseMediaType(fileData.getContentType()));
                }
                // Add proper S3 headers using file hash
                h.set("ETag", "\"" + fileData.getHash().substring(0, 16) + "\"");
                h.set("x-amz-request-id", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
                h.set("x-amz-id-2", java.util.UUID.randomUUID().toString());
                h.set("Server", "MinIO");
                
                return new ResponseEntity<>(fileData.getData(), h, HttpStatus.OK);
            } catch (Exception e) {
                log.error("Error getting object: ", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        });
    }

    // PUT /{bucket}/{**key} - S3 compatible PUT object
    @PutMapping(value = "/{bucket}/**")
    public Mono<ResponseEntity<Void>> putObject(
            @PathVariable String bucket,
            ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        // Extract key by removing the bucket part: /bucket/key -> key
        String key = path.substring(("/" + bucket + "/").length());
        log.info("PUT object: bucket={}, key={}", bucket, key);
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    try {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);

                        String contentType = exchange.getRequest().getHeaders().getFirst("Content-Type");
                        
                        // Use deduplication service instead of direct MinIO upload
                        String etag = deduplicationService.putObject(bucket, key, bytes, contentType);
                        
                        // Return proper S3 response headers
                        HttpHeaders headers = new HttpHeaders();
                        headers.set("ETag", "\"" + etag + "\"");
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
    @DeleteMapping(value = "/{bucket}/**")
    public Mono<ResponseEntity<Void>> deleteObject(
            @PathVariable String bucket,
            ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        // Extract key by removing the bucket part: /bucket/key -> key
        String key = path.substring(("/" + bucket + "/").length());
        log.info("DELETE object: bucket={}, key={}", bucket, key);
        return Mono.fromCallable(() -> {
            try {
                boolean deleted = deduplicationService.deleteObject(bucket, key);
                
                if (!deleted) {
                    return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                }
                
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
    @RequestMapping(value = "/{bucket}/**", method = RequestMethod.HEAD)
    public Mono<ResponseEntity<Void>> headObject(
            @PathVariable String bucket,
            ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        // Extract key by removing the bucket part: /bucket/key -> key
        String key = path.substring(("/" + bucket + "/").length());
        log.info("HEAD object: bucket={}, key={}", bucket, key);
        return Mono.fromCallable(() -> {
            try {
                DeduplicationService.FileData fileData = deduplicationService.getObject(bucket, key);
                if (fileData == null) {
                    return new ResponseEntity<>(HttpStatus.NOT_FOUND);
                }
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentLength(fileData.getSize());
                if (fileData.getContentType() != null) {
                    headers.setContentType(MediaType.parseMediaType(fileData.getContentType()));
                }
                headers.set("ETag", "\"" + fileData.getHash().substring(0, 16) + "\"");
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
    @GetMapping("/presign/{bucket}/**")
    public Mono<Map<String, String>> presignObject(
            @PathVariable String bucket,
            ServerWebExchange exchange,
            @RequestParam(defaultValue = "GET") String method,
            @RequestParam(defaultValue = "600") Integer expirySeconds) {
        String path = exchange.getRequest().getPath().value();
        String key = path.substring(path.lastIndexOf("/presign/" + bucket + "/") + ("/presign/" + bucket + "/").length());
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