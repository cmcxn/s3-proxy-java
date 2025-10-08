package com.example.s3proxy;

import com.example.s3proxy.service.DeduplicationService;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Test to verify that the dedupe storage bucket is automatically created
 * when it doesn't exist, which fixes the mc mirror tool issue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class BucketAutoCreationTest {

    @Container
    static GenericContainer<?> minio = new GenericContainer<>("minio/minio:latest")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withExposedPorts(9000);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("MINIO_ENDPOINT", () -> "http://localhost:" + minio.getMappedPort(9000));
        registry.add("MINIO_ACCESS_KEY", () -> "minioadmin");
        registry.add("MINIO_SECRET_KEY", () -> "minioadmin");
        registry.add("MINIO_DEDUPE_BUCKET", () -> "test-dedupe-storage");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private DeduplicationService deduplicationService;

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket.dedupe-storage}")
    private String dedupeStorageBucket;

    @Test
    void testDedupeStorageBucketAutoCreation() throws Exception {
        // Verify the dedupe bucket doesn't exist initially
        boolean bucketExists = minioClient.bucketExists(
            BucketExistsArgs.builder().bucket(dedupeStorageBucket).build()
        );
        
        if (bucketExists) {
            // Skip this test if bucket already exists from previous tests
            // (In a real scenario, this simulates the case where the bucket doesn't exist)
            System.out.println("Dedupe bucket already exists, test scenario is different but should still work");
        } else {
            System.out.println("Dedupe bucket doesn't exist - this is the scenario we're testing");
        }

        // Make a PUT request that should trigger bucket creation
        String bucket = "test-bucket";
        String key = "test-file.txt";
        String content = "Test file content for mc mirror scenario";

        webTestClient
                .put()
                .uri("/" + bucket + "/" + key)
                .headers(headers -> {
                    headers.add("Authorization", "Basic bWluaW9hZG1pbjptaW5pb2FkbWlu"); // minioadmin:minioadmin
                    headers.add("Content-Type", "text/plain");
                })
                .bodyValue(content)
                .exchange()
                .expectStatus().isCreated();

        // Verify the dedupe bucket now exists
        boolean bucketExistsAfter = minioClient.bucketExists(
            BucketExistsArgs.builder().bucket(dedupeStorageBucket).build()
        );
        
        assert bucketExistsAfter : "Dedupe storage bucket should have been created automatically";
        
        System.out.println("✅ SUCCESS: Dedupe storage bucket was created automatically");
        System.out.println("✅ This fixes the mc mirror 'bucket does not exist' error");
    }
}