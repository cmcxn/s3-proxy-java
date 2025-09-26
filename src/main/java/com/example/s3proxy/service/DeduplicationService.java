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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;

@Service
@Transactional
public class DeduplicationService {
    
    private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);
    
    private final FileRepository fileRepository;
    private final UserFileRepository userFileRepository;
    private final HashService hashService;
    private final MinioClient minioClient;
    
    public DeduplicationService(FileRepository fileRepository, 
                               UserFileRepository userFileRepository,
                               HashService hashService,
                               MinioClient minioClient) {
        this.fileRepository = fileRepository;
        this.userFileRepository = userFileRepository;
        this.hashService = hashService;
        this.minioClient = minioClient;
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
            // File exists - increment reference count
            log.info("File already exists in storage, incrementing reference count: hash={}", hash);
            fileEntity = existingFile.get();
            fileEntity.incrementReferenceCount();
            fileRepository.save(fileEntity);
            etag = hash.substring(0, 16); // Use hash prefix as ETag
        } else {
            // File doesn't exist - store in MinIO and create database record
            log.info("New file - storing in MinIO: hash={}", hash);
            
            String storagePath = "dedupe-data/" + hash;
            
            try (InputStream inputStream = new ByteArrayInputStream(data)) {
                PutObjectArgs.Builder argsBuilder = PutObjectArgs.builder()
                        .bucket("dedupe-storage") // Use dedicated bucket for content-addressed storage
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
            // Update existing mapping - first decrement old file reference
            UserFileEntity oldMapping = existingUserFile.get();
            FileEntity oldFile = oldMapping.getFile();
            oldFile.decrementReferenceCount();
            fileRepository.save(oldFile);
            
            // Update to new file
            oldMapping.setFile(fileEntity);
            userFileRepository.save(oldMapping);
        } else {
            // Create new mapping
            UserFileEntity userFile = new UserFileEntity(bucket, key, fileEntity);
            userFileRepository.save(userFile);
        }
        
        log.info("Successfully stored file: bucket={}, key={}, hash={}, new_reference_count={}", 
                bucket, key, hash, fileEntity.getReferenceCount());
        
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
        
        FileEntity fileEntity = userFile.get().getFile();
        log.debug("Found file: hash={}, storage_path={}", fileEntity.getHashValue(), fileEntity.getStoragePath());
        
        // Get data from MinIO using storage path
        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket("dedupe-storage") // Use dedicated bucket for content-addressed storage
                        .object(fileEntity.getStoragePath())
                        .build())) {
            
            byte[] data = response.readAllBytes();
            return new FileData(data, fileEntity.getContentType(), fileEntity.getHashValue(), fileEntity.getSize());
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
        
        FileEntity fileEntity = userFile.get().getFile();
        String hash = fileEntity.getHashValue();
        String storagePath = fileEntity.getStoragePath();
        
        // Remove user file mapping
        userFileRepository.delete(userFile.get());
        
        // Decrement reference count
        fileEntity.decrementReferenceCount();
        fileRepository.save(fileEntity);
        
        // If reference count reaches 0, delete from MinIO and database
        if (fileEntity.getReferenceCount() == 0) {
            log.info("Reference count reached 0, deleting from MinIO: hash={}, storage_path={}", hash, storagePath);
            
            try {
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket("dedupe-storage") // Use dedicated bucket for content-addressed storage
                                .object(storagePath)
                                .build());
            } catch (Exception e) {
                log.warn("Failed to delete object from MinIO: {}", storagePath, e);
            }
            
            fileRepository.delete(fileEntity);
            log.info("File completely removed: hash={}", hash);
        } else {
            log.info("File still has {} references, keeping in storage: hash={}", fileEntity.getReferenceCount(), hash);
        }
        
        return true;
    }
    
    public static class FileData {
        private final byte[] data;
        private final String contentType;
        private final String hash;
        private final long size;
        
        public FileData(byte[] data, String contentType, String hash, long size) {
            this.data = data;
            this.contentType = contentType;
            this.hash = hash;
            this.size = size;
        }
        
        public byte[] getData() { return data; }
        public String getContentType() { return contentType; }
        public String getHash() { return hash; }
        public long getSize() { return size; }
    }
}