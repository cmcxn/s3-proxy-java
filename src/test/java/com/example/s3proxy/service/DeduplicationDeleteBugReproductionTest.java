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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to demonstrate the fix for the issue described in the problem statement:
 * "DeduplicationService deleteObject 时候，当文件只有一次被引用，删除时候依然会出现
 * File still has 1 references, keeping in storage: hash=..., 检查是不是数据库事务同步问题。"
 * 
 * Translation: "When DeduplicationService deleteObject is called and the file has only 
 * one reference, during deletion it still shows 'File still has 1 references, keeping 
 * in storage: hash=...'. Check if this is a database transaction synchronization issue."
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "MINIO_ENDPOINT=http://localhost:9999", // Use invalid endpoint to avoid real MinIO dependency
    "MINIO_ACCESS_KEY=test",
    "MINIO_SECRET_KEY=test"
})
@Transactional
class DeduplicationDeleteBugReproductionTest {

    @Autowired
    private FileRepository fileRepository;
    
    @Autowired
    private UserFileRepository userFileRepository;

    /**
     * This test reproduces the exact issue reported:
     * - Create a file with reference_count = 1
     * - Call decrementReferenceCount 
     * - Verify that subsequent findById returns reference_count = 0 (not 1)
     * 
     * Before the fix: findById would return the cached entity with reference_count = 1
     * After the fix: findById returns the updated entity with reference_count = 0
     */
    @Test 
    void testReproduceOriginalIssue_SingleReferenceStillShowsOneAfterDecrement() {
        // Setup: Create a file entity with hash matching the one from the problem statement
        String problematicHash = "7803b91bce7e095176b17d342784624a21cd7a43ff4e9d1ecd834c10b2c4e61c";
        FileEntity fileEntity = new FileEntity(problematicHash, 1000L, "application/octet-stream", 
                                              "dedupe-data/" + problematicHash);
        fileEntity = fileRepository.save(fileEntity);
        
        // Verify initial state: reference_count should be 1
        assertEquals(1, fileEntity.getReferenceCount(), 
                    "Initial reference count should be 1 (this represents the single reference case)");
        
        // Create a user file mapping to represent the single reference
        UserFileEntity userFile = new UserFileEntity("test-bucket", "test-key", fileEntity);
        userFileRepository.save(userFile);
        
        // This is the critical operation that was causing the issue:
        // When we decrement the reference count, the database gets updated correctly,
        // but the JPA persistence context cache was not being cleared
        int updatedRows = fileRepository.decrementReferenceCount(fileEntity.getId());
        assertEquals(1, updatedRows, "Should update exactly 1 row");
        
        // THE FIX: This is where the bug was occurring
        // Before fix: This would return fileEntity with reference_count = 1 (from JPA cache)
        // After fix: This returns fileEntity with reference_count = 0 (from database)
        FileEntity refreshedEntity = fileRepository.findById(fileEntity.getId()).orElse(null);
        assertNotNull(refreshedEntity, "Entity should still exist in database");
        
        // This assertion validates the fix for the original issue
        assertEquals(0, refreshedEntity.getReferenceCount(), 
                    "Reference count should be 0 after decrement. " +
                    "If this is 1, it means the database transaction synchronization issue still exists!");
        
        // Additional validation: The entity should be eligible for deletion
        // (This would be the next step in DeduplicationService.deleteObject)
        if (refreshedEntity.getReferenceCount() == 0) {
            // This is what should happen: file gets deleted
            fileRepository.delete(refreshedEntity);
            assertFalse(fileRepository.findById(fileEntity.getId()).isPresent(), 
                       "File should be completely deleted when reference count reaches 0");
        } else {
            fail("The original bug still exists: reference count shows " + 
                 refreshedEntity.getReferenceCount() + " instead of 0");
        }
    }
    
    /**
     * Complementary test to show the fix works for multiple references too
     */
    @Test
    void testMultipleReferencesDecrementCorrectly() {
        String hash = "multi-ref-test-hash";
        FileEntity fileEntity = new FileEntity(hash, 500L, "text/plain", "dedupe-data/" + hash);
        fileEntity = fileRepository.save(fileEntity);
        
        // Simulate multiple references by incrementing
        fileRepository.incrementReferenceCount(fileEntity.getId());  // Now 2
        fileRepository.incrementReferenceCount(fileEntity.getId());  // Now 3
        
        // Verify we have 3 references
        FileEntity current = fileRepository.findById(fileEntity.getId()).get();
        assertEquals(3, current.getReferenceCount(), "Should have 3 references");
        
        // Decrement once
        fileRepository.decrementReferenceCount(fileEntity.getId());
        
        // The fix should ensure this shows 2, not 3
        current = fileRepository.findById(fileEntity.getId()).get();
        assertEquals(2, current.getReferenceCount(), 
                    "Should show 2 references after decrement (validates persistence context sync)");
        
        // Decrement again
        fileRepository.decrementReferenceCount(fileEntity.getId());
        current = fileRepository.findById(fileEntity.getId()).get();
        assertEquals(1, current.getReferenceCount(), "Should show 1 reference");
        
        // Final decrement - this would trigger the original bug scenario
        fileRepository.decrementReferenceCount(fileEntity.getId());
        current = fileRepository.findById(fileEntity.getId()).get();
        assertEquals(0, current.getReferenceCount(), 
                    "Should show 0 references - validates fix for the reported issue!");
    }
}