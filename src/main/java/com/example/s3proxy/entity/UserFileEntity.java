package com.example.s3proxy.entity;

import com.example.s3proxy.util.Sha256Utils;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "minio_user_files", 
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_bucket_key_sha256", columnNames = {"bucket", "object_key_sha256"})
       },
       indexes = {
           @Index(name = "idx_minio_user_files_bucket", columnList = "bucket"),
           @Index(name = "idx_minio_user_files_object_key_sha256", columnList = "object_key_sha256"),
           @Index(name = "idx_minio_user_files_bucket_key_sha256", columnList = "bucket,object_key_sha256"),
           @Index(name = "idx_minio_user_files_file_id", columnList = "file_id"),
           @Index(name = "idx_minio_user_files_created_at", columnList = "created_at")
       }
)
public class UserFileEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "bucket", nullable = false, length = 255)
    private String bucket;

    @Column(name = "object_key", nullable = false, columnDefinition = "TEXT")
    private String key;
    
    @Column(name = "object_key_sha256", nullable = false, length = 64)
    private String keySha256;
    
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
        this.keySha256 = Sha256Utils.calculateSha256(key);
        this.file = file;
    }
    
    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    
    public String getKey() { return key; }
    public void setKey(String key) { 
        this.key = key;
        this.keySha256 = Sha256Utils.calculateSha256(key);
    }
    
    public String getKeySha256() { return keySha256; }
    public void setKeySha256(String keySha256) { this.keySha256 = keySha256; }
    
    public FileEntity getFile() { return file; }
    public void setFile(FileEntity file) { this.file = file; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}