package com.example.s3proxy.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_files", 
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_bucket_key", columnNames = {"bucket", "object_key"})
       },
       indexes = {
           @Index(name = "idx_user_files_bucket", columnList = "bucket"),
           @Index(name = "idx_user_files_object_key", columnList = "object_key"),
           @Index(name = "idx_user_files_bucket_key", columnList = "bucket,object_key"),
           @Index(name = "idx_user_files_file_id", columnList = "file_id"),
           @Index(name = "idx_user_files_created_at", columnList = "created_at")
       }
)
public class UserFileEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "bucket", nullable = false, length = 255)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 1000)
    private String key;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    public UserFileEntity() {
        this.createdAt = LocalDateTime.now();
    }
    
    public UserFileEntity(String bucket, String key, FileEntity file) {
        this();
        this.bucket = bucket;
        this.key = key;
        this.file = file;
    }
    
    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    
    public FileEntity getFile() { return file; }
    public void setFile(FileEntity file) { this.file = file; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}