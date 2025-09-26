package com.example.s3proxy.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "files", 
       indexes = {
           @Index(name = "idx_files_hash_value", columnList = "hash_value", unique = true),
           @Index(name = "idx_files_reference_count", columnList = "reference_count"),
           @Index(name = "idx_files_created_at", columnList = "created_at"),
           @Index(name = "idx_files_size", columnList = "size")
       }
)
public class FileEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "hash_value", unique = true, nullable = false, length = 64)
    private String hashValue;
    
    @Column(name = "size", nullable = false)
    private Long size;
    
    @Column(name = "content_type", length = 255)
    private String contentType;
    
    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;
    
    @Column(name = "reference_count", nullable = false)
    private Integer referenceCount = 0;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    public FileEntity() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    
    public FileEntity(String hashValue, Long size, String contentType, String storagePath) {
        this();
        this.hashValue = hashValue;
        this.size = size;
        this.contentType = contentType;
        this.storagePath = storagePath;
        this.referenceCount = 1;
    }
    
    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getHashValue() { return hashValue; }
    public void setHashValue(String hashValue) { this.hashValue = hashValue; }
    
    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }
    
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    
    public Integer getReferenceCount() { return referenceCount; }
    public void setReferenceCount(Integer referenceCount) { this.referenceCount = referenceCount; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    
    public void incrementReferenceCount() {
        this.referenceCount++;
        this.updatedAt = LocalDateTime.now();
    }
    
    public void decrementReferenceCount() {
        if (this.referenceCount > 0) {
            this.referenceCount--;
        }
        this.updatedAt = LocalDateTime.now();
    }
}