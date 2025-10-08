package com.example.s3proxy;

import com.example.s3proxy.service.DeduplicationService;
import com.example.s3proxy.service.MultipartUploadService;
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
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * S3-compatible controller that handles requests at root level for MinIO SDK compatibility.
 * This controller provides S3-compatible endpoints without path prefixes,
 * making it compatible with MinIO SDK which doesn't allow paths in endpoints.
 */
@RestController
public class S3CompatibleController {
    private static final Logger log = LoggerFactory.getLogger(S3CompatibleController.class);
    private static final DateTimeFormatter S3_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private final MinioClient minio;
    private final DeduplicationService deduplicationService;
    private final MultipartUploadService multipartUploadService;

    public S3CompatibleController(MinioClient minio,
                                  DeduplicationService deduplicationService,
                                  MultipartUploadService multipartUploadService) {
        this.minio = minio;
        this.deduplicationService = deduplicationService;
        this.multipartUploadService = multipartUploadService;
    }

    @PostMapping(value = "/{bucket}/**")
    public Mono<ResponseEntity<String>> handleMultipartPost(
            @PathVariable String bucket,
            ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String key = path.substring(("/" + bucket + "/").length());
        log.info("POST object request: bucket={}, key={}, query={}", bucket, key, exchange.getRequest().getQueryParams());

        if (exchange.getRequest().getQueryParams().containsKey("uploads")) {
            Map<String, String> metadata = extractUserMetadata(exchange.getRequest().getHeaders());
            String contentType = exchange.getRequest().getHeaders().getFirst("Content-Type");
            String uploadId = multipartUploadService.createUpload(bucket, key, contentType, metadata);

            HttpHeaders headers = createStandardS3Headers();
            headers.setContentType(MediaType.APPLICATION_XML);

            StringBuilder xml = new StringBuilder();
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            xml.append("<InitiateMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n");
            xml.append("  <Bucket>").append(escapeXml(bucket)).append("</Bucket>\n");
            xml.append("  <Key>").append(escapeXml(key)).append("</Key>\n");
            xml.append("  <UploadId>").append(uploadId).append("</UploadId>\n");
            xml.append("</InitiateMultipartUploadResult>");

            return Mono.just(new ResponseEntity<>(xml.toString(), headers, HttpStatus.OK));
        }

        String uploadId = exchange.getRequest().getQueryParams().getFirst("uploadId");
        if (uploadId != null) {
            return DataBufferUtils.join(exchange.getRequest().getBody())
                    .flatMap(dataBuffer -> {
                        try {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            List<Integer> partNumbers = parseCompleteMultipartRequest(bytes);
                            MultipartUploadService.CompletedUpload completedUpload =
                                    multipartUploadService.completeUpload(uploadId, partNumbers);

                            if (!completedUpload.getBucket().equals(bucket) || !completedUpload.getKey().equals(key)) {
                                log.warn("Upload metadata mismatch for uploadId={}: request bucket/key {}:{}, stored {}:{}",
                                        uploadId, bucket, key, completedUpload.getBucket(), completedUpload.getKey());
                            }

                            String etag = deduplicationService.putObject(
                                    bucket,
                                    key,
                                    completedUpload.getData(),
                                    completedUpload.getContentType(),
                                    completedUpload.getMetadata());

                            HttpHeaders headers = createStandardS3Headers();
                            headers.setContentType(MediaType.APPLICATION_XML);
                            headers.set("ETag", "\"" + etag + "\"");

                            StringBuilder xml = new StringBuilder();
                            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                            xml.append("<CompleteMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n");
                            xml.append("  <Bucket>").append(escapeXml(bucket)).append("</Bucket>\n");
                            xml.append("  <Key>").append(escapeXml(key)).append("</Key>\n");
                            xml.append("  <ETag>\"").append(etag).append("\"</ETag>\n");
                            xml.append("</CompleteMultipartUploadResult>");

                            return Mono.just(new ResponseEntity<>(xml.toString(), headers, HttpStatus.OK));
                        } catch (IllegalArgumentException e) {
                            log.warn("Failed to complete multipart upload {}: {}", uploadId, e.getMessage());
                            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(""));
                        } catch (Exception e) {
                            log.error("Error completing multipart upload {}", uploadId, e);
                            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(""));
                        } finally {
                            DataBufferUtils.release(dataBuffer);
                        }
                    });
        }

        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(""));
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
            @RequestParam(value = "marker", required = false, defaultValue = "") String marker,
            @RequestParam(value = "list-type", required = false, defaultValue = "1") String listType,
            @RequestParam(value = "continuation-token", required = false, defaultValue = "") String continuationToken,
            @RequestParam(value = "start-after", required = false, defaultValue = "") String startAfter) {
        return Mono.fromCallable(() -> {
            try {
                log.info("Listing objects: bucket={}, prefix='{}', delimiter='{}', maxKeys={}, listType={}, continuationToken='{}', startAfter='{}'",
                        bucket, prefix, delimiter, maxKeys, listType, continuationToken, startAfter);

                // Use deduplication service to list objects from database instead of MinIO
                List<DeduplicationService.ObjectInfo> objectInfos = deduplicationService.listObjects(bucket, prefix);

                List<DeduplicationService.ObjectInfo> filteredItems = new ArrayList<>();
                List<String> commonPrefixes = new ArrayList<>();
                boolean isTruncated = false;
                String nextMarkerOrToken = null;

                boolean isListV2 = "2".equals(listType);

                // Determine the effective marker based on the list type
                String effectiveMarker = marker;
                if (isListV2) {
                    if (!continuationToken.isEmpty()) {
                        effectiveMarker = continuationToken;
                    } else if (!startAfter.isEmpty()) {
                        effectiveMarker = startAfter;
                    } else {
                        effectiveMarker = "";
                    }
                }

                for (DeduplicationService.ObjectInfo objectInfo : objectInfos) {
                    String objectName = objectInfo.getKey();

                    // Skip objects that come before the marker
                    if (!effectiveMarker.isEmpty() && objectName.compareTo(effectiveMarker) <= 0) {
                        continue;
                    }

                    // When using delimiter, handle common prefixes for directory-style listing
                    if (delimiter != null && !delimiter.isEmpty()) {
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

                    filteredItems.add(objectInfo);
                    // Stop if we've reached maxKeys
                    if (filteredItems.size() >= maxKeys) {
                        isTruncated = true;
                        nextMarkerOrToken = objectName; // Set next marker/token to the last object processed
                        break;
                    }
                }

                // Build S3-compatible XML response
                StringBuilder xmlBuilder = new StringBuilder();
                xmlBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                xmlBuilder.append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">\n");
                xmlBuilder.append("  <Name>").append(escapeXml(bucket)).append("</Name>\n");
                xmlBuilder.append("  <Prefix>").append(escapeXml(prefix)).append("</Prefix>\n");

                if (isListV2) {
                    if (!continuationToken.isEmpty()) {
                        xmlBuilder.append("  <ContinuationToken>")
                                .append(escapeXml(continuationToken))
                                .append("</ContinuationToken>\n");
                    }
                    if (!startAfter.isEmpty()) {
                        xmlBuilder.append("  <StartAfter>")
                                .append(escapeXml(startAfter))
                                .append("</StartAfter>\n");
                    }
                    xmlBuilder.append("  <KeyCount>").append(filteredItems.size()).append("</KeyCount>\n");
                } else {
                    xmlBuilder.append("  <Marker>").append(escapeXml(marker)).append("</Marker>\n");
                }

                xmlBuilder.append("  <MaxKeys>").append(maxKeys).append("</MaxKeys>\n");
                if (delimiter != null && !delimiter.isEmpty()) {
                    xmlBuilder.append("  <Delimiter>").append(escapeXml(delimiter)).append("</Delimiter>\n");
                }
                xmlBuilder.append("  <IsTruncated>").append(isTruncated ? "true" : "false").append("</IsTruncated>\n");

                // Add continuation/next marker elements when results are truncated
                if (isTruncated && nextMarkerOrToken != null) {
                    if (isListV2) {
                        xmlBuilder.append("  <NextContinuationToken>")
                                .append(escapeXml(nextMarkerOrToken))
                                .append("</NextContinuationToken>\n");
                    } else {
                        xmlBuilder.append("  <NextMarker>")
                                .append(escapeXml(nextMarkerOrToken))
                                .append("</NextMarker>\n");
                    }
                }

                // Add objects to XML
                for (DeduplicationService.ObjectInfo objectInfo : filteredItems) {
                    xmlBuilder.append("  <Contents>\n");
                    xmlBuilder.append("    <Key>").append(escapeXml(objectInfo.getKey())).append("</Key>\n");

                    String lastModified = formatS3Timestamp(objectInfo.getLastModified());
                    if (!lastModified.isEmpty()) {
                        xmlBuilder.append("    <LastModified>").append(lastModified).append("</LastModified>\n");
                    }

                    // ETag should include quotes and not be XML escaped
                    String etag = objectInfo.getEtag().substring(0, Math.min(16, objectInfo.getEtag().length()));
                    if (!etag.startsWith("\"")) {
                        etag = "\"" + etag + "\"";
                    }
                    xmlBuilder.append("    <ETag>").append(etag).append("</ETag>\n");
                    xmlBuilder.append("    <Size>").append(objectInfo.getSize()).append("</Size>\n");
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
                
                log.info("Successfully listed {} objects for bucket: {}", filteredItems.size(), bucket);
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

    private String formatS3Timestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "";
        }
        return timestamp
                .atOffset(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS)
                .format(S3_TIMESTAMP_FORMATTER);
    }

    private void applyLastModifiedHeader(HttpHeaders headers, LocalDateTime lastModified) {
        if (headers == null || lastModified == null) {
            return;
        }
        String httpDate = lastModified
                .atOffset(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        headers.set(HttpHeaders.LAST_MODIFIED, httpDate);
    }

    private void applyUserMetadata(HttpHeaders headers, Map<String, String> metadata) {
        if (headers == null || metadata == null || metadata.isEmpty()) {
            return;
        }
        metadata.forEach((key, value) -> headers.set("x-amz-meta-" + key, value));
    }

    private HttpHeaders createStandardS3Headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-amz-request-id", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        headers.set("x-amz-id-2", java.util.UUID.randomUUID().toString());
        headers.set("Server", "MinIO");
        return headers;
    }

    private Map<String, String> extractUserMetadata(HttpHeaders headers) {
        if (headers == null) {
            return Collections.emptyMap();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name == null) {
                return;
            }
            String lower = name.toLowerCase();
            if (lower.startsWith("x-amz-meta-")) {
                String key = lower.substring("x-amz-meta-".length());
                if (!key.isBlank() && values != null && !values.isEmpty()) {
                    metadata.put(key, values.get(0));
                }
            }
        });
        return metadata;
    }

    private List<Integer> parseCompleteMultipartRequest(byte[] body) throws Exception {
        if (body == null || body.length == 0) {
            return Collections.emptyList();
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(new ByteArrayInputStream(body));

        NodeList partNodes = document.getElementsByTagName("Part");
        List<Integer> partNumbers = new ArrayList<>();
        for (int i = 0; i < partNodes.getLength(); i++) {
            Element partElement = (Element) partNodes.item(i);
            String partNumberText = getFirstChildTextContent(partElement, "PartNumber");
            if (partNumberText != null && !partNumberText.isBlank()) {
                partNumbers.add(Integer.parseInt(partNumberText.trim()));
            }
        }
        return partNumbers;
    }

    private String getFirstChildTextContent(Element parent, String tagName) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes == null || nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent();
    }

    private Mono<ResponseEntity<String>> handleCopyObject(String destinationBucket,
                                                          String destinationKey,
                                                          ServerWebExchange exchange,
                                                          String rawCopySource) {
        String metadataDirective = exchange.getRequest().getHeaders().getFirst("x-amz-metadata-directive");
        boolean replaceMetadata = metadataDirective != null && metadataDirective.equalsIgnoreCase("REPLACE");

        String decodedSource = URLDecoder.decode(rawCopySource, StandardCharsets.UTF_8);
        String source = decodedSource.startsWith("/") ? decodedSource.substring(1) : decodedSource;
        int slashIndex = source.indexOf('/');
        if (slashIndex < 0) {
            log.warn("Invalid x-amz-copy-source header: {}", rawCopySource);
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(""));
        }

        String sourceBucket = source.substring(0, slashIndex);
        String sourceKey = source.substring(slashIndex + 1);

        Map<String, String> metadata = replaceMetadata
                ? extractUserMetadata(exchange.getRequest().getHeaders())
                : Collections.emptyMap();

        return Mono.fromCallable(() -> {
            try {
                DeduplicationService.CopyResult result = deduplicationService.copyObject(
                        sourceBucket,
                        sourceKey,
                        destinationBucket,
                        destinationKey,
                        metadata,
                        replaceMetadata);

                if (result == null) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body("<Error><Code>NoSuchKey</Code></Error>");
                }

                HttpHeaders responseHeaders = new HttpHeaders();
                responseHeaders.setContentType(MediaType.APPLICATION_XML);
                responseHeaders.set("x-amz-request-id", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
                responseHeaders.set("x-amz-id-2", java.util.UUID.randomUUID().toString());
                responseHeaders.set("Server", "MinIO");

                StringBuilder xml = new StringBuilder();
                xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                xml.append("<CopyObjectResult>\n");
                xml.append("  <LastModified>").append(formatS3Timestamp(result.getLastModified())).append("</LastModified>\n");
                xml.append("  <ETag>\"").append(result.getEtag()).append("\"</ETag>\n");
                xml.append("</CopyObjectResult>");

                return new ResponseEntity<>(xml.toString(), responseHeaders, HttpStatus.OK);
            } catch (Exception e) {
                log.error("Error processing copy request", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("<Error><Code>InternalError</Code></Error>");
            }
        });
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

                String hashPrefix = fileData.getHash().substring(0, Math.min(16, fileData.getHash().length()));
                h.set("ETag", "\"" + hashPrefix + "\"");
                applyLastModifiedHeader(h, fileData.getLastModified());
                h.set("x-amz-request-id", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
                h.set("x-amz-id-2", java.util.UUID.randomUUID().toString());
                h.set("Server", "MinIO");
                h.set(HttpHeaders.ACCEPT_RANGES, "bytes");
                applyUserMetadata(h, fileData.getMetadata());

                String rangeHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.RANGE);
                if (rangeHeader != null && !rangeHeader.isEmpty()) {
                    try {
                        List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                        if (ranges.size() == 1) {
                            HttpRange range = ranges.get(0);
                            long fileSize = fileData.getSize();
                            long rangeStart = range.getRangeStart(fileSize);
                            long rangeEnd = range.getRangeEnd(fileSize);
                            if (rangeStart >= fileSize) {
                                HttpHeaders errorHeaders = new HttpHeaders();
                                errorHeaders.set(HttpHeaders.CONTENT_RANGE, "bytes */" + fileSize);
                                return new ResponseEntity<>(null, errorHeaders, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
                            }

                            int length = (int) (rangeEnd - rangeStart + 1);
                            byte[] body = new byte[length];
                            System.arraycopy(fileData.getData(), (int) rangeStart, body, 0, length);

                            h.setContentLength(length);
                            h.set(HttpHeaders.CONTENT_RANGE, String.format("bytes %d-%d/%d", rangeStart, rangeEnd, fileSize));
                            return new ResponseEntity<>(body, h, HttpStatus.PARTIAL_CONTENT);
                        }
                    } catch (IllegalArgumentException ex) {
                        log.warn("Invalid range header '{}': {}", rangeHeader, ex.getMessage());
                        // Fall back to full response
                    }
                }

                h.setContentLength(fileData.getSize());
                return new ResponseEntity<>(fileData.getData(), h, HttpStatus.OK);
            } catch (Exception e) {
                log.error("Error getting object: ", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        });
    }

    // PUT /{bucket}/{**key} - S3 compatible PUT object
    @PutMapping(value = "/{bucket}/**")
    public Mono<ResponseEntity<String>> putObject(
            @PathVariable String bucket,
            ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        // Extract key by removing the bucket part: /bucket/key -> key
        String key = path.substring(("/" + bucket + "/").length());
        log.info("PUT object: bucket={}, key={}", bucket, key);
        String copySource = exchange.getRequest().getHeaders().getFirst("x-amz-copy-source");
        if (copySource != null && !copySource.isBlank()) {
            return handleCopyObject(bucket, key, exchange, copySource);
        }
        String uploadId = exchange.getRequest().getQueryParams().getFirst("uploadId");
        String partNumberParam = exchange.getRequest().getQueryParams().getFirst("partNumber");
        if (uploadId != null && partNumberParam != null) {
            int partNumber;
            try {
                partNumber = Integer.parseInt(partNumberParam);
            } catch (NumberFormatException ex) {
                log.warn("Invalid partNumber '{}' for upload {}", partNumberParam, uploadId);
                return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(""));
            }

            return DataBufferUtils.join(exchange.getRequest().getBody())
                    .flatMap(dataBuffer -> {
                        try {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            String etag = multipartUploadService.storePart(uploadId, partNumber, bytes);

                            HttpHeaders headers = createStandardS3Headers();
                            headers.set("ETag", "\"" + etag + "\"");

                            return Mono.just(new ResponseEntity<>(null, headers, HttpStatus.OK));
                        } catch (IllegalArgumentException e) {
                            log.warn("Failed to store multipart upload part: {}", e.getMessage());
                            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(""));
                        } finally {
                            DataBufferUtils.release(dataBuffer);
                        }
                    });
        }
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    try {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);

                        String contentType = exchange.getRequest().getHeaders().getFirst("Content-Type");
                        
                        Map<String, String> metadata = extractUserMetadata(exchange.getRequest().getHeaders());

                        // Use deduplication service instead of direct MinIO upload
                        String etag = deduplicationService.putObject(bucket, key, bytes, contentType, metadata);

                        // Return proper S3 response headers
                        HttpHeaders headers = createStandardS3Headers();
                        headers.set("ETag", "\"" + etag + "\"");

                        return Mono.just(new ResponseEntity<>(null, headers, HttpStatus.CREATED));
                    } catch (Exception e) {
                        log.error("Error putting object: ", e);
                        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
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
        String uploadId = exchange.getRequest().getQueryParams().getFirst("uploadId");
        if (uploadId != null) {
            boolean aborted = multipartUploadService.abortUpload(uploadId);
            if (aborted) {
                HttpHeaders headers = createStandardS3Headers();
                return Mono.just(new ResponseEntity<>(headers, HttpStatus.NO_CONTENT));
            }
            return Mono.just(new ResponseEntity<>(HttpStatus.NOT_FOUND));
        }
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
                String hashPrefix = fileData.getHash().substring(0, Math.min(16, fileData.getHash().length()));
                headers.set("ETag", "\"" + hashPrefix + "\"");
                applyLastModifiedHeader(headers, fileData.getLastModified());
                headers.set("x-amz-request-id", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
                headers.set("x-amz-id-2", java.util.UUID.randomUUID().toString());
                headers.set("Server", "MinIO");
                headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
                applyUserMetadata(headers, fileData.getMetadata());

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