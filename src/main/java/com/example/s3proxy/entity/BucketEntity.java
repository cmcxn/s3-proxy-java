package com.example.s3proxy.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "minio_buckets", indexes = {
        @Index(name = "idx_minio_buckets_name", columnList = "name", unique = true),
        @Index(name = "idx_minio_buckets_created_at", columnList = "created_at")
})
public class BucketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255, unique = true)
    private String name;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public BucketEntity() {
        this.createdAt = LocalDateTime.now();
    }

    public BucketEntity(String name) {
        this();
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
