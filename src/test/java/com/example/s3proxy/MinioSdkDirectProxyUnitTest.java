package com.example.s3proxy;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.http.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test that simulates MinIO SDK direct access against the proxy service
 * without requiring Docker containers. This helps identify the core compatibility issues.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                    "MINIO_ENDPOINT=http://localhost:9000",
                    "MINIO_ACCESS_KEY=minioadmin",
                    "MINIO_SECRET_KEY=minioadmin"
                })
public class MinioSdkDirectProxyUnitTest {

    @LocalServerPort
    private int port;

    private MinioClient proxyMinioClient;

    @BeforeEach
    void setUp() throws Exception {
        // Client pointing to the proxy service at root level (S3CompatibleController) - this is what we're testing
        proxyMinioClient = MinioClient.builder()
                .endpoint("http://localhost:" + port)
                .credentials("minioadmin", "minioadmin")
                .region("us-east-1")
                .build();
    }

    @Test
    void testMinioSdkEndpointStructure() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "test-object.txt";
        String content = "Hello, World!";

        // Test PUT operation through MinIO SDK
        // This should demonstrate the compatibility issues
        Exception exception = assertThrows(Exception.class, () -> {
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes())) {
                proxyMinioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(inputStream, content.length(), -1)
                        .contentType("text/plain")
                        .build());
            }
        });

        // Log the exception to understand the compatibility issue
        System.out.println("=== MinIO SDK PUT Exception ===");
        System.out.println("Exception type: " + exception.getClass().getSimpleName());
        System.out.println("Exception message: " + exception.getMessage());
        if (exception.getCause() != null) {
            System.out.println("Root cause: " + exception.getCause().getMessage());
        }
        System.out.println("=============================");
    }

    @Test 
    void testMinioSdkGetObjectStructure() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "test-object.txt";

        // Test GET operation through MinIO SDK
        Exception exception = assertThrows(Exception.class, () -> {
            try (GetObjectResponse response = proxyMinioClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
                response.readAllBytes();
            }
        });

        // Log the exception to understand the compatibility issue
        System.out.println("=== MinIO SDK GET Exception ===");
        System.out.println("Exception type: " + exception.getClass().getSimpleName());
        System.out.println("Exception message: " + exception.getMessage());
        if (exception.getCause() != null) {
            System.out.println("Root cause: " + exception.getCause().getMessage());
        }
        System.out.println("==============================");
    }

    @Test
    void testMinioSdkStatObjectStructure() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "test-object.txt";

        // Test STAT operation through MinIO SDK (HEAD request)
        Exception exception = assertThrows(Exception.class, () -> {
            proxyMinioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
        });

        // Log the exception to understand the compatibility issue
        System.out.println("=== MinIO SDK STAT Exception ===");
        System.out.println("Exception type: " + exception.getClass().getSimpleName());
        System.out.println("Exception message: " + exception.getMessage());
        if (exception.getCause() != null) {
            System.out.println("Root cause: " + exception.getCause().getMessage());
        }
        System.out.println("===============================");
    }

    @Test
    void testMinioSdkPresignedUrlStructure() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "test-object.txt";

        // Test presigned URL generation through MinIO SDK
        try {
            String presignedUrl = proxyMinioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(600)
                            .build());
                            
            System.out.println("=== MinIO SDK Presigned URL Success ===");
            System.out.println("✅ Presigned URL generated successfully!");
            System.out.println("URL: " + presignedUrl);
            System.out.println("Note: URL generation works, but the URL itself points to the proxy endpoint");
            System.out.println("which now has authentication - this is an improvement!");
            System.out.println("=======================================");
            
            // Verify URL contains our proxy endpoint
            assertTrue(presignedUrl.contains("localhost:" + port), 
                      "Generated URL should contain proxy endpoint");
            
        } catch (Exception e) {
            // If there's still an exception, log it
            System.out.println("=== MinIO SDK Presigned URL Exception ===");
            System.out.println("Exception type: " + e.getClass().getSimpleName());
            System.out.println("Exception message: " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("Root cause: " + e.getCause().getMessage());
            }
            System.out.println("========================================");
            throw e; // Re-throw to fail the test if there's an unexpected exception
        }
    }
}