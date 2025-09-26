package com.example.s3proxy.test;

import com.example.s3proxy.entity.UserFileEntity;
import com.example.s3proxy.entity.FileEntity;
import com.example.s3proxy.repository.UserFileRepository;
import com.example.s3proxy.repository.FileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class SchemaVerificationTest {
    
    @Autowired
    private FileRepository fileRepository;
    
    @Autowired
    private UserFileRepository userFileRepository;
    
    @Test
    public void testNewSchemaAndSHA256Functionality() {
        // Create a file entity
        FileEntity file = new FileEntity("test-hash", 100L, "text/plain", "storage/path");
        fileRepository.save(file);
        
        // Create a user file entity with SHA256 functionality
        String testKey = "test-object-key";
        UserFileEntity userFile = new UserFileEntity("test-bucket", testKey, file);
        
        // Verify SHA256 was calculated
        assertNotNull(userFile.getKeySha256());
        assertEquals(64, userFile.getKeySha256().length()); // SHA256 hash should be 64 chars
        
        // Save the user file
        userFileRepository.save(userFile);
        
        // Test SHA256-based lookup
        var foundUserFile = userFileRepository.findByBucketAndKey("test-bucket", testKey);
        assertTrue(foundUserFile.isPresent());
        assertEquals(testKey, foundUserFile.get().getKey());
        assertEquals(userFile.getKeySha256(), foundUserFile.get().getKeySha256());
        
        // Verify the file relationship
        assertEquals(file.getId(), foundUserFile.get().getFile().getId());
    }
}