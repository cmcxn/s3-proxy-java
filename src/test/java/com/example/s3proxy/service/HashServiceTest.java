package com.example.s3proxy.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HashServiceTest {
    
    private final HashService hashService = new HashService();
    
    @Test
    void testSHA256Calculation() throws Exception {
        // Test with sample data
        byte[] testData = "Hello, World!".getBytes();
        String expectedHash = "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f";
        
        String actualHash = hashService.calculateSHA256(testData);
        
        assertEquals(expectedHash, actualHash);
    }
    
    @Test
    void testSHA256Consistency() throws Exception {
        // Test that same data produces same hash
        byte[] testData = "Test content for deduplication".getBytes();
        
        String hash1 = hashService.calculateSHA256(testData);
        String hash2 = hashService.calculateSHA256(testData);
        
        assertEquals(hash1, hash2, "Same content should produce same hash");
    }
    
    @Test
    void testSHA256DifferentData() throws Exception {
        // Test that different data produces different hashes
        byte[] testData1 = "Content 1".getBytes();
        byte[] testData2 = "Content 2".getBytes();
        
        String hash1 = hashService.calculateSHA256(testData1);
        String hash2 = hashService.calculateSHA256(testData2);
        
        assertNotEquals(hash1, hash2, "Different content should produce different hashes");
    }
    
    @Test
    void testEmptyData() throws Exception {
        // Test with empty data
        byte[] emptyData = new byte[0];
        String expectedEmptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        
        String actualHash = hashService.calculateSHA256(emptyData);
        
        assertEquals(expectedEmptyHash, actualHash);
    }
}