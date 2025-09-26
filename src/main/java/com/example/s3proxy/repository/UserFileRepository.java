package com.example.s3proxy.repository;

import com.example.s3proxy.entity.UserFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserFileRepository extends JpaRepository<UserFileEntity, Long> {
    
    Optional<UserFileEntity> findByBucketAndKey(String bucket, String key);
    
    @Modifying
    @Query("DELETE FROM UserFileEntity uf WHERE uf.bucket = :bucket AND uf.key = :key")
    int deleteByBucketAndKey(@Param("bucket") String bucket, @Param("key") String key);
    
    @Query("SELECT COUNT(uf) FROM UserFileEntity uf WHERE uf.file.id = :fileId")
    long countByFileId(@Param("fileId") Long fileId);
}