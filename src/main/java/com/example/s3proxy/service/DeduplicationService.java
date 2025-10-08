package com.example.s3proxy.service;

import com.example.s3proxy.entity.FileEntity;
import com.example.s3proxy.entity.UserFileEntity;
import com.example.s3proxy.repository.FileRepository;
import com.example.s3proxy.repository.UserFileRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class DeduplicationService {
    
    private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);
    
    private final FileRepository fileRepository;
    private final UserFileRepository userFileRepository;
    private final HashService hashService;
    private final MinioClient minioClient;
    private final String dedupeStorageBucket;
    
    public DeduplicationService(FileRepository fileRepository, 
                               UserFileRepository userFileRepository,
                               HashService hashService,
                               MinioClient minioClient,
                               @Value("${minio.bucket.dedupe-storage}") String dedupeStorageBucket) {
        this.fileRepository = fileRepository;
        this.userFileRepository = userFileRepository;
        this.hashService = hashService;
        this.minioClient = minioClient;
        this.dedupeStorageBucket = dedupeStorageBucket;
    }
    
    /**
     * Store a file with deduplication logic
     */
    public String putObject(String bucket, String key, byte[] data, String contentType) throws Exception {
        log.info("Storing file with deduplication: bucket={}, key={}, size={}", bucket, key, data.length);
        
        // Calculate hash
        String hash = hashService.calculateSHA256(data);
        log.debug("Calculated SHA-256 hash: {}", hash);
        
        // Check if file already exists
        Optional<FileEntity> existingFile = fileRepository.findByHashValue(hash);
        
        FileEntity fileEntity;
        String etag;
        
        if (existingFile.isPresent()) {
            // File exists - increment reference count atomically
            log.info("File already exists in storage, incrementing reference count: hash={}", hash);
            fileEntity = existingFile.get();
            fileRepository.incrementReferenceCount(fileEntity.getId());
            // Refresh entity to get updated reference count
            fileEntity = fileRepository.findById(fileEntity.getId()).orElse(fileEntity);
            etag = hash.substring(0, 16); // Use hash prefix as ETag
        } else {
            // File doesn't exist - store in MinIO and create database record
            log.info("New file - storing in MinIO: hash={}", hash);
            
            String storagePath = "dedupe-data/" + hash;
            
            try (InputStream inputStream = new ByteArrayInputStream(data)) {
                PutObjectArgs.Builder argsBuilder = PutObjectArgs.builder()
                        .bucket(dedupeStorageBucket) // Use configurable bucket for content-addressed storage
                        .object(storagePath)
                        .stream(inputStream, data.length, -1);
                
                if (contentType != null) {
                    argsBuilder.contentType(contentType);
                }
                
                minioClient.putObject(argsBuilder.build());
            }
            
            // Create file record
            fileEntity = new FileEntity(hash, (long) data.length, contentType, storagePath);
            fileRepository.save(fileEntity);
            etag = hash.substring(0, 16);
        }
        
        // Create or update user file mapping
        Optional<UserFileEntity> existingUserFile = userFileRepository.findByBucketAndKey(bucket, key);
        if (existingUserFile.isPresent()) {
            // Update existing mapping - first decrement old file reference atomically
            UserFileEntity oldMapping = existingUserFile.get();
            FileEntity oldFile = oldMapping.getFile();
            fileRepository.decrementReferenceCount(oldFile.getId());

            // Update to new file
            oldMapping.setFile(fileEntity);
            oldMapping.setCreatedAt(LocalDateTime.now());
            userFileRepository.save(oldMapping);
        } else {
            // Create new mapping
            UserFileEntity userFile = new UserFileEntity(bucket, key, fileEntity);
            userFileRepository.save(userFile);
        }
        
        // Get the final entity state for logging
        FileEntity finalEntity = fileRepository.findById(fileEntity.getId()).orElse(fileEntity);
        log.info("Successfully stored file: bucket={}, key={}, hash={}, new_reference_count={}", 
                bucket, key, hash, finalEntity.getReferenceCount());
        
        return etag;
    }
    
    /**
     * Get file data by bucket and key
     */
    public FileData getObject(String bucket, String key) throws Exception {
        log.info("Getting file: bucket={}, key={}", bucket, key);
        
        Optional<UserFileEntity> userFile = userFileRepository.findByBucketAndKey(bucket, key);
        if (userFile.isEmpty()) {
            log.debug("File not found: bucket={}, key={}", bucket, key);
            return null;
        }
        
        UserFileEntity userFileEntity = userFile.get();
        FileEntity fileEntity = userFileEntity.getFile();
        log.debug("Found file: hash={}, storage_path={}", fileEntity.getHashValue(), fileEntity.getStoragePath());

        // Get data from MinIO using storage path
        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(dedupeStorageBucket) // Use configurable bucket for content-addressed storage
                        .object(fileEntity.getStoragePath())
                        .build())) {

            byte[] data = response.readAllBytes();
            return new FileData(
                    data,
                    fileEntity.getContentType(),
                    fileEntity.getHashValue(),
                    fileEntity.getSize(),
                    userFileEntity.getCreatedAt());
        }
    }
    
    /**
     * Delete file by bucket and key (with reference counting)
     */
    public boolean deleteObject(String bucket, String key) throws Exception {
        log.info("Deleting file: bucket={}, key={}", bucket, key);
        
        Optional<UserFileEntity> userFile = userFileRepository.findByBucketAndKey(bucket, key);
        if (userFile.isEmpty()) {
            log.debug("File not found for deletion: bucket={}, key={}", bucket, key);
            return false;
        }

        UserFileEntity entity = userFile.get();
        FileEntity fileEntity = entity.getFile();
        String hash = fileEntity.getHashValue();
        String storagePath = fileEntity.getStoragePath();
        
        // Remove user file mapping
        int c = userFileRepository.deleteByBucketAndKey(bucket, key);
        log.info("Successfully deleted file: bucket={}, key={} change = {}", bucket, key,c);
        // Decrement reference count atomically
        int updatedRows = fileRepository.decrementReferenceCount(fileEntity.getId());
        log.info("decrementReferenceCount={}", updatedRows);
        // Refresh entity to get updated reference count
        fileEntity = fileRepository.findById(fileEntity.getId()).orElse(null);
        
        // If reference count reaches 0, delete from MinIO and database
        if (fileEntity != null && fileEntity.getReferenceCount() == 0) {
            log.info("Reference count reached 0, deleting from MinIO: hash={}, storage_path={}", hash, storagePath);
            
            try {
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(dedupeStorageBucket) // Use configurable bucket for content-addressed storage
                                .object(storagePath)
                                .build());
            } catch (Exception e) {
                log.warn("Failed to delete object from MinIO: {}", storagePath, e);
            }
            
            fileRepository.delete(fileEntity);
            log.info("File completely removed: hash={}", hash);
        } else if (fileEntity != null) {
            log.info("File still has {} references, keeping in storage: hash={}", fileEntity.getReferenceCount(), hash);
        } else {
            log.warn("FileEntity became null after decrement operation for hash={}", hash);
        }
        
        return true;
    }
    
    /**
     * List objects in a bucket with optional prefix filtering
     */
    public List<ObjectInfo> listObjects(String bucket, String prefix) throws Exception {
        log.info("Listing objects: bucket={}, prefix='{}'", bucket, prefix);
        
        List<UserFileEntity> userFiles;
        if (prefix != null && !prefix.isEmpty()) {
            userFiles = userFileRepository.findByBucketAndKeyStartingWith(bucket, prefix);
        } else {
            userFiles = userFileRepository.findByBucketOrderByKey(bucket);
        }
        
        log.debug("Found {} user files", userFiles.size());
        
        return userFiles.stream()
                .map(uf -> new ObjectInfo(
                    uf.getKey(),
                    uf.getFile().getSize(),
                    uf.getCreatedAt(),
                    uf.getFile().getHashValue(),
                    uf.getFile().getContentType()
                ))
                .collect(java.util.stream.Collectors.toList());
    }
    
    public static class ObjectInfo {
        private final String key;
        private final long size;
        private final java.time.LocalDateTime lastModified;
        private final String etag;
        private final String contentType;
        
        public ObjectInfo(String key, long size, java.time.LocalDateTime lastModified, String etag, String contentType) {
            this.key = key;
            this.size = size;
            this.lastModified = lastModified;
            this.etag = etag;
            this.contentType = contentType;
        }
        
        public String getKey() { return key; }
        public long getSize() { return size; }
        public java.time.LocalDateTime getLastModified() { return lastModified; }
        public String getEtag() { return etag; }
        public String getContentType() { return contentType; }
    }
    
    public static class FileData {
        private final byte[] data;
        private final String contentType;
        private final String hash;
        private final long size;
        private final LocalDateTime lastModified;

        public FileData(byte[] data, String contentType, String hash, long size, LocalDateTime lastModified) {
            this.data = data;
            this.contentType = contentType;
            this.hash = hash;
            this.size = size;
            this.lastModified = lastModified;
        }

        public byte[] getData() { return data; }
        public String getContentType() { return contentType; }
        public String getHash() { return hash; }
        public long getSize() { return size; }
        public LocalDateTime getLastModified() { return lastModified; }
    }
}
