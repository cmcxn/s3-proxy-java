package com.example.s3proxy.repository;

import com.example.s3proxy.entity.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FileRepository extends JpaRepository<FileEntity, Long> {
    
    Optional<FileEntity> findByHashValue(String hashValue);
    
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FileEntity f SET f.referenceCount = f.referenceCount + 1, f.updatedAt = CURRENT_TIMESTAMP WHERE f.id = :id")
    void incrementReferenceCount(@Param("id") Long id);
    
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FileEntity f SET f.referenceCount = f.referenceCount - 1, f.updatedAt = CURRENT_TIMESTAMP WHERE f.id = :id AND f.referenceCount > 0")
    int decrementReferenceCount(@Param("id") Long id);

}