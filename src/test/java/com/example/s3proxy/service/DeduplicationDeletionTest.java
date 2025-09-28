package com.example.s3proxy.service;

import com.example.s3proxy.entity.FileEntity;
import com.example.s3proxy.entity.UserFileEntity;
import com.example.s3proxy.repository.FileRepository;
import com.example.s3proxy.repository.UserFileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "MINIO_ENDPOINT=http://localhost:9999", // Use invalid endpoint to avoid real MinIO dependency
    "MINIO_ACCESS_KEY=test",
    "MINIO_SECRET_KEY=test"
})
class DeduplicationDeletionTest {

    @Autowired
    private FileRepository fileRepository;
    
    @Autowired
    private UserFileRepository userFileRepository;
    
    // This test focuses on the core database transaction issue
    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW) 
    void testAtomicReferenceCountDecrement_PersistenceContextSync() {
        // This test specifically validates the fix for the issue:
        // "File still has 1 references, keeping in storage" when it should show 0
        
        String hash = "test-hash-atomic-fix";
        FileEntity fileEntity = new FileEntity(hash, 100L, "text/plain", "dedupe-data/" + hash);
        fileEntity = fileRepository.save(fileEntity);
        
        Long fileId = fileEntity.getId();
        assertEquals(1, fileEntity.getReferenceCount(), "Initial reference count should be 1");
        
        // The critical test: After calling decrementReferenceCount, 
        // findById should return the entity with the updated reference count
        int updatedRows = fileRepository.decrementReferenceCount(fileId);
        assertEquals(1, updatedRows, "Should update 1 row");
        
        // THIS IS THE FIX: Before the fix, this would return referenceCount = 1
        // After the fix (with clearAutomatically = true), it should return referenceCount = 0
        FileEntity refreshed = fileRepository.findById(fileId).orElse(null);
        assertNotNull(refreshed, "Entity should still exist");
        assertEquals(0, refreshed.getReferenceCount(), 
                    "Reference count should be 0 - this validates the persistence context synchronization fix!");
        
        // Test further decrements are blocked by the WHERE clause
        int updatedRows2 = fileRepository.decrementReferenceCount(fileId);
        assertEquals(0, updatedRows2, "Should not update any rows when reference count is already 0");
        
        // Verify it stays at 0
        refreshed = fileRepository.findById(fileId).orElse(null);
        assertNotNull(refreshed, "Entity should still exist");
        assertEquals(0, refreshed.getReferenceCount(), "Reference count should remain 0");
    }
    
    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void testAtomicReferenceCountIncrement_PersistenceContextSync() {
        String hash = "test-hash-increment";
        FileEntity fileEntity = new FileEntity(hash, 100L, "text/plain", "dedupe-data/" + hash);
        fileEntity = fileRepository.save(fileEntity);
        
        Long fileId = fileEntity.getId();
        assertEquals(1, fileEntity.getReferenceCount(), "Initial reference count should be 1");
        
        // Test increment synchronization  
        fileRepository.incrementReferenceCount(fileId);
        
        // This should show the incremented value due to clearAutomatically = true
        FileEntity refreshed = fileRepository.findById(fileId).orElse(null);
        assertNotNull(refreshed, "Entity should exist");
        assertEquals(2, refreshed.getReferenceCount(), 
                    "Reference count should be 2 after increment");
    }
    
    @Test
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void testMultipleDecrements_ToZero() {
        // Test the scenario that causes the reported issue
        String hash = "test-hash-multi-decrement";
        FileEntity fileEntity = new FileEntity(hash, 100L, "text/plain", "dedupe-data/" + hash);
        fileEntity = fileRepository.save(fileEntity);
        
        Long fileId = fileEntity.getId();
        
        // Simulate having 3 references
        fileRepository.incrementReferenceCount(fileId);
        fileRepository.incrementReferenceCount(fileId);
        
        FileEntity current = fileRepository.findById(fileId).get();
        assertEquals(3, current.getReferenceCount(), "Should have 3 references");
        
        // Decrement one by one
        fileRepository.decrementReferenceCount(fileId);
        current = fileRepository.findById(fileId).get();
        assertEquals(2, current.getReferenceCount(), "Should have 2 references");
        
        fileRepository.decrementReferenceCount(fileId);
        current = fileRepository.findById(fileId).get();
        assertEquals(1, current.getReferenceCount(), "Should have 1 reference");
        
        // The critical test: This decrement should take it to 0 and be visible immediately
        fileRepository.decrementReferenceCount(fileId);
        current = fileRepository.findById(fileId).get();
        assertEquals(0, current.getReferenceCount(), 
                    "Should have 0 references - this is the core fix for the reported issue!");
    }
    
    @Test  
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void testDecrementReferenceCount_WhenAlreadyZero_ShouldNotUpdate() {
        String hash = "test-hash-zero-safety";
        FileEntity fileEntity = new FileEntity(hash, 100L, "text/plain", "dedupe-data/" + hash);
        fileEntity.setReferenceCount(0); // Set to 0 initially
        fileEntity = fileRepository.save(fileEntity);
        
        // Try to decrement when already 0 - this should be blocked by the WHERE clause
        int updatedRows = fileRepository.decrementReferenceCount(fileEntity.getId());
        assertEquals(0, updatedRows, "Should not update any rows when reference count is already 0");
        
        // Verify it stays at 0
        FileEntity refreshed = fileRepository.findById(fileEntity.getId()).orElse(null);
        assertNotNull(refreshed, "Entity should exist");
        assertEquals(0, refreshed.getReferenceCount(), "Reference count should remain 0");
    }
}