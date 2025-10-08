package com.example.s3proxy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory implementation of S3 multipart upload state management.
 * <p>
 * This implementation is intended for compatibility with S3 clients that
 * automatically switch to multipart uploads for large files. Parts are stored
 * in memory until the upload is completed or aborted.
 * </p>
 */
@Service
public class MultipartUploadService {

    private static final Logger log = LoggerFactory.getLogger(MultipartUploadService.class);

    private final ConcurrentMap<String, MultipartUploadState> uploads = new ConcurrentHashMap<>();

    public String createUpload(String bucket, String key, String contentType, Map<String, String> metadata) {
        String uploadId = UUID.randomUUID().toString();
        MultipartUploadState state = new MultipartUploadState(bucket, key, contentType, metadata);
        uploads.put(uploadId, state);
        log.info("Created multipart upload: uploadId={}, bucket={}, key={}", uploadId, bucket, key);
        return uploadId;
    }

    public String storePart(String uploadId, int partNumber, byte[] data) {
        MultipartUploadState state = uploads.get(uploadId);
        if (state == null) {
            throw new IllegalArgumentException("Upload ID not found: " + uploadId);
        }
        if (partNumber <= 0) {
            throw new IllegalArgumentException("Invalid part number: " + partNumber);
        }
        state.parts.put(partNumber, data);
        String etag = calculateMd5Hex(data);
        log.info("Stored multipart upload part: uploadId={}, partNumber={}, size={}, etag={}"
                , uploadId, partNumber, data.length, etag);
        return etag;
    }

    public CompletedUpload completeUpload(String uploadId, List<Integer> orderedParts) {
        MultipartUploadState state = uploads.remove(uploadId);
        if (state == null) {
            throw new IllegalArgumentException("Upload ID not found: " + uploadId);
        }

        List<Integer> partsInOrder;
        if (orderedParts == null || orderedParts.isEmpty()) {
            partsInOrder = new ArrayList<>(state.parts.keySet());
            Collections.sort(partsInOrder);
        } else {
            partsInOrder = new ArrayList<>(orderedParts);
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            for (Integer partNumber : partsInOrder) {
                byte[] part = state.parts.get(partNumber);
                if (part == null) {
                    throw new IllegalArgumentException("Missing part " + partNumber + " for upload " + uploadId);
                }
                outputStream.write(part);
            }
            byte[] data = outputStream.toByteArray();
            log.info("Completed multipart upload: uploadId={}, combinedSize={}", uploadId, data.length);
            return new CompletedUpload(state.bucket, state.key, state.contentType, state.metadata, data);
        } catch (Exception e) {
            uploads.put(uploadId, state);
            throw new IllegalStateException("Failed to assemble multipart upload: " + uploadId, e);
        }
    }

    public boolean abortUpload(String uploadId) {
        MultipartUploadState removed = uploads.remove(uploadId);
        if (removed != null) {
            log.info("Aborted multipart upload: uploadId={}, bucket={}, key={}", uploadId, removed.bucket, removed.key);
            return true;
        }
        return false;
    }

    private String calculateMd5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    private static class MultipartUploadState {
        private final String bucket;
        private final String key;
        private final String contentType;
        private final Map<String, String> metadata;
        private final ConcurrentMap<Integer, byte[]> parts = new ConcurrentHashMap<>();

        private MultipartUploadState(String bucket, String key, String contentType, Map<String, String> metadata) {
            this.bucket = bucket;
            this.key = key;
            this.contentType = contentType;
            this.metadata = metadata == null ? Collections.emptyMap() : new ConcurrentHashMap<>(metadata);
        }
    }

    public static class CompletedUpload {
        private final String bucket;
        private final String key;
        private final String contentType;
        private final Map<String, String> metadata;
        private final byte[] data;

        private CompletedUpload(String bucket, String key, String contentType, Map<String, String> metadata, byte[] data) {
            this.bucket = bucket;
            this.key = key;
            this.contentType = contentType;
            this.metadata = metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(metadata);
            this.data = data;
        }

        public String getBucket() {
            return bucket;
        }

        public String getKey() {
            return key;
        }

        public String getContentType() {
            return contentType;
        }

        public Map<String, String> getMetadata() {
            return metadata;
        }

        public byte[] getData() {
            return data;
        }
    }
}
