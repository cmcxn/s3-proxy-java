package com.example.s3proxy.repository;

import com.example.s3proxy.entity.UserFileEntity;
import com.example.s3proxy.util.Sha256Utils;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserFileRepository extends JpaRepository<UserFileEntity, Long> {
    
    /**
     * Find user file by bucket and key using SHA256-based lookup with verification
     * This method first matches on the SHA256 hash for performance, then verifies the actual key
     */
    @Query("SELECT uf FROM UserFileEntity uf WHERE uf.bucket = :bucket AND uf.keySha256 = :keySha256 AND uf.key = :key")
    Optional<UserFileEntity> findByBucketAndKeySha256AndKey(@Param("bucket") String bucket, 
                                                             @Param("keySha256") String keySha256, 
                                                             @Param("key") String key);
    
    /**
     * Convenience method that calculates SHA256 automatically
     */
    default Optional<UserFileEntity> findByBucketAndKey(String bucket, String key) {
        String keySha256 = Sha256Utils.calculateSha256(key);
        return findByBucketAndKeySha256AndKey(bucket, keySha256, key);
    }
    
    @Modifying
    @Query("DELETE FROM UserFileEntity uf WHERE uf.bucket = :bucket AND uf.keySha256 = :keySha256 AND uf.key = :key")
    int deleteByBucketAndKeySha256AndKey(@Param("bucket") String bucket, 
                                         @Param("keySha256") String keySha256, 
                                         @Param("key") String key);
    
    /**
     * Convenience method that calculates SHA256 automatically for deletion
     */
    default int deleteByBucketAndKey(String bucket, String key) {
        String keySha256 = Sha256Utils.calculateSha256(key);
        return deleteByBucketAndKeySha256AndKey(bucket, keySha256, key);
    }
    
    @Query("SELECT COUNT(uf) FROM UserFileEntity uf WHERE uf.file.id = :fileId")
    long countByFileId(@Param("fileId") Long fileId);
    
    // Find all user files in a bucket with optional prefix
    @Query("SELECT uf FROM UserFileEntity uf WHERE uf.bucket = :bucket AND uf.key LIKE CONCAT(:prefix, '%') ORDER BY uf.key")
    List<UserFileEntity> findByBucketAndKeyStartingWith(@Param("bucket") String bucket, @Param("prefix") String prefix);
    
    // Find all user files in a bucket
    @Query("SELECT uf FROM UserFileEntity uf WHERE uf.bucket = :bucket ORDER BY uf.key")
    List<UserFileEntity> findByBucketOrderByKey(@Param("bucket") String bucket);
}