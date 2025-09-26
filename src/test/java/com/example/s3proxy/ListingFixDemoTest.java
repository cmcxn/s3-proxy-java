package com.example.s3proxy;

import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Focused test to demonstrate the fix for object listing with deduplication service.
 * This test specifically validates the scenario from the problem statement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class ListingFixDemoTest {
    
    @Container
    static GenericContainer<?> minioContainer = new GenericContainer<>("minio/minio:latest")
            .withExposedPorts(9000)
            .withEnv("MINIO_ACCESS_KEY", "minioadmin")
            .withEnv("MINIO_SECRET_KEY", "minioadmin")
            .withCommand("server", "/data");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("MINIO_ENDPOINT", () -> "http://localhost:" + minioContainer.getMappedPort(9000));
        registry.add("MINIO_ACCESS_KEY", () -> "minioadmin");
        registry.add("MINIO_SECRET_KEY", () -> "minioadmin");
    }

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;
    private MinioClient directMinioClient;
    private final String testBucket = "abc";

    @BeforeEach
    void setUp() throws Exception {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        directMinioClient = MinioClient.builder()
                .endpoint("http://localhost:" + minioContainer.getMappedPort(9000))
                .credentials("minioadmin", "minioadmin")
                .build();

        // Create test bucket in MinIO
        if (!directMinioClient.bucketExists(BucketExistsArgs.builder().bucket(testBucket).build())) {
            directMinioClient.makeBucket(MakeBucketArgs.builder().bucket(testBucket).build());
        }
        
        // Create dedupe-storage bucket for deduplication service
        if (!directMinioClient.bucketExists(BucketExistsArgs.builder().bucket("dedupe-storage").build())) {
            directMinioClient.makeBucket(MakeBucketArgs.builder().bucket("dedupe-storage").build());
        }
    }

    @Test
    void testListingFixForDeduplication() throws Exception {
        // Test the exact scenario from problem statement:
        // Upload file and then list with prefix to verify it's now returned
        
        String testContent = "# FAQ\n\nThis is a frequently asked questions file.";
        String testKey = "gt/docqa/local_file/UID_yanfei/KB_123456/f3aeda1b3c834859862dda455c0a1a01/FAQ_zh.md";
        String listPrefix = "gt/docqa/local_file/UID_yanfei/KB_123456/f3aeda1b3c834859862dda455c0a1a01/";
        
        String credentials = "minioadmin:minioadmin";
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        String authHeader = "Basic " + encodedCredentials;
        
        // 1. Upload the file using PUT
        webTestClient.put()
                .uri("/" + testBucket + "/" + testKey)
                .header("Authorization", authHeader)
                .header("Content-Type", "text/markdown")
                .bodyValue(testContent.getBytes(StandardCharsets.UTF_8))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("ETag");
        
        // 2. List objects with the prefix - this should NOW return the file
        // (Before the fix, this returned 0 objects)
        webTestClient.get()
                .uri("/" + testBucket + "?prefix=" + listPrefix)
                .header("Authorization", authHeader)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/xml")
                .expectBody(String.class)
                .value(body -> {
                    System.out.println("=== LISTING FIX DEMONSTRATION ===");
                    System.out.println("List response for prefix '" + listPrefix + "':");
                    System.out.println(body);
                    System.out.println("");
                    
                    // Should contain the uploaded file - this was the main bug
                    assert body.contains("<Key>" + testKey + "</Key>") : "Response should contain the uploaded file key";
                    assert body.contains("<Contents>") : "Should contain Contents element";
                    assert !body.contains("Successfully listed 0 objects") : "Should not list 0 objects (was the bug)";
                    
                    System.out.println("✅ FIX VERIFIED: File is now properly listed!");
                    System.out.println("✅ Before fix: Would return 0 objects");
                    System.out.println("✅ After fix: Correctly returns the FAQ_zh.md file");
                    System.out.println("==========================================");
                });
    }
}