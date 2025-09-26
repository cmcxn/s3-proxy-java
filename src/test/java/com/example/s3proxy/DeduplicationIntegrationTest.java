package com.example.s3proxy;

import com.example.s3proxy.service.DeduplicationService;
import com.example.s3proxy.service.HashService;
import com.example.s3proxy.repository.FileRepository;
import com.example.s3proxy.repository.UserFileRepository;
import com.example.s3proxy.entity.FileEntity;
import com.example.s3proxy.entity.UserFileEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "MINIO_ENDPOINT=http://localhost:9999", // Use invalid endpoint to avoid real MinIO dependency
    "MINIO_ACCESS_KEY=test",
    "MINIO_SECRET_KEY=test"
})
@Transactional
class DeduplicationIntegrationTest {

    @Autowired
    private FileRepository fileRepository;
    
    @Autowired
    private UserFileRepository userFileRepository;
    
    @Autowired 
    private HashService hashService;

    @Test
    void testHashServiceIntegration() throws Exception {
        byte[] testData = "Hello World".getBytes();
        String hash = hashService.calculateSHA256(testData);
        
        assertNotNull(hash);
        assertEquals(64, hash.length()); // SHA-256 produces 64 hex characters
    }
    
    @Test
    void testFileEntityOperations() {
        // Test creating and saving a file entity
        FileEntity file = new FileEntity("test-hash", 100L, "text/plain", "test-path");
        FileEntity saved = fileRepository.save(file);
        
        assertNotNull(saved.getId());
        assertEquals("test-hash", saved.getHashValue());
        assertEquals(1, saved.getReferenceCount());
        
        // Test finding by hash
        var found = fileRepository.findByHashValue("test-hash");
        assertTrue(found.isPresent());
        assertEquals(saved.getId(), found.get().getId());
    }
    
    @Test
    void testUserFileEntityOperations() {
        // First create a file
        FileEntity file = new FileEntity("user-test-hash", 200L, "text/plain", "user-test-path");
        FileEntity savedFile = fileRepository.save(file);
        
        // Then create a user file mapping
        UserFileEntity userFile = new UserFileEntity("test-bucket", "test/key", savedFile);
        UserFileEntity savedUserFile = userFileRepository.save(userFile);
        
        assertNotNull(savedUserFile.getId());
        assertEquals("test-bucket", savedUserFile.getBucket());
        assertEquals("test/key", savedUserFile.getKey());
        
        // Test finding by bucket and key
        var found = userFileRepository.findByBucketAndKey("test-bucket", "test/key");
        assertTrue(found.isPresent());
        assertEquals(savedUserFile.getId(), found.get().getId());
    }
    
    @Test
    void testReferenceCountOperations() {
        // Create a file with initial reference count of 1
        FileEntity file = new FileEntity("ref-test-hash", 300L, "text/plain", "ref-test-path");
        FileEntity saved = fileRepository.save(file);
        assertEquals(1, saved.getReferenceCount());
        
        // Test increment
        saved.incrementReferenceCount();
        fileRepository.save(saved);
        
        FileEntity updated = fileRepository.findById(saved.getId()).orElse(null);
        assertNotNull(updated);
        assertEquals(2, updated.getReferenceCount());
        
        // Test decrement
        updated.decrementReferenceCount();
        fileRepository.save(updated);
        
        FileEntity decremented = fileRepository.findById(saved.getId()).orElse(null);
        assertNotNull(decremented);
        assertEquals(1, decremented.getReferenceCount());
    }
}