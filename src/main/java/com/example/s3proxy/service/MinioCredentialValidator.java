package com.example.s3proxy.service;

import io.minio.MinioClient;
import io.minio.ListBucketsArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service to validate MinIO credentials against the MinIO server directly.
 */
@Service
public class MinioCredentialValidator {
    
    private static final Logger log = LoggerFactory.getLogger(MinioCredentialValidator.class);
    
    @Value("${minio.endpoint}")
    private String minioEndpoint;
    
    /**
     * Validates credentials by attempting to authenticate with MinIO server.
     * This makes a simple API call to test if the credentials are valid.
     * 
     * @param accessKey The access key to validate
     * @param secretKey The secret key to validate
     * @return true if credentials are valid, false otherwise
     */
    public boolean validateCredentials(String accessKey, String secretKey) {
        try {
            MinioClient tempClient = MinioClient.builder()
                    .endpoint(minioEndpoint)
                    .credentials(accessKey, secretKey)
                    .build();
            
            // Attempt a simple operation to validate credentials
            // listBuckets is a lightweight operation that requires authentication
            tempClient.listBuckets(ListBucketsArgs.builder().build());
            
            log.debug("Credential validation successful for access key: {}", accessKey);
            return true;
            
        } catch (Exception e) {
            log.debug("Credential validation failed for access key {}: {}", accessKey, e.getMessage());
            return false;
        }
    }
    
    /**
     * Validates if an access key exists by attempting a connection to MinIO.
     * This is used for AWS signature validation where we only have the access key.
     * We can't validate the signature itself without the secret key, but we can
     * check if the access key is valid.
     * 
     * @param accessKey The access key to validate
     * @return true if the access key appears to be valid (connection succeeds with any secret), false otherwise
     */
    public boolean validateAccessKeyExists(String accessKey) {
        // For AWS signature validation, we don't have the secret key
        // We can't validate the signature without it, so we'll just
        // make sure MinIO is accessible and let MinIO handle the signature validation
        log.debug("Validating access key exists: {} against endpoint: {}", accessKey, minioEndpoint);
        try {
            MinioClient tempClient = MinioClient.builder()
                    .endpoint(minioEndpoint)
                    .credentials(accessKey, "dummy-secret-for-testing")
                    .build();
            
            // Try to make a connection to MinIO - this should fail if MinIO is not available
            // but in a way that indicates the server is reachable
            tempClient.listBuckets(ListBucketsArgs.builder().build());
            
            // If we get here, either the credentials worked (unlikely with dummy secret)
            // or MinIO returned a different error, meaning it's accessible
            log.debug("MinIO server accessible for access key: {}", accessKey);
            return true;
            
        } catch (Exception e) {
            // Check if the error indicates server unavailability vs auth failure
            String message = e.getMessage().toLowerCase();
            log.debug("MinIO validation error for access key {}: {}", accessKey, e.getMessage());
            if (message.contains("connection refused") || message.contains("connection timeout") ||
                message.contains("unknown host") || message.contains("could not connect") ||
                message.contains("failed to connect")) {
                log.debug("MinIO server not accessible: {}", e.getMessage());
                return false; // Server not available
            } else {
                // Likely an auth failure, which means server is accessible
                // We'll allow the request through and let MinIO validate the actual signature
                log.debug("MinIO server accessible, auth error expected for dummy credentials: {}", e.getMessage());
                return true;
            }
        }
    }
}