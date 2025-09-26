package com.example.s3proxy;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class NestedPathTest {

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
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MinioClient minioClient;

    @BeforeEach
    void setUp() throws Exception {
        // Create the test bucket if it doesn't exist
        String bucket = "abc";
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    @Test
    void testNestedPathGet() {
        String bucket = "abc";
        String key = "sss/FAQ_zh.md";
        String content = "# FAQ\nThis is a test file";

        // First upload the file
        String testContent = content;
        webTestClient
                .put()
                .uri("/" + bucket + "/" + key) // Use string concatenation instead of URI template
                .headers(headers -> {
                    headers.add("Authorization", "Basic bWluaW9hZG1pbjptaW5pb2FkbWlu"); // minioadmin:minioadmin
                    headers.add("Content-Type", "text/markdown");
                })
                .bodyValue(testContent)
                .exchange()
                .expectStatus().isCreated();

        // Wait a bit for the operation to complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Now try to get the file with nested path
        webTestClient
                .get()
                .uri("/" + bucket + "/" + key) // Use string concatenation
                .headers(headers -> {
                    headers.add("Authorization", "Basic bWluaW9hZG1pbjptaW5pb2FkbWlu"); // minioadmin:minioadmin
                })
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo(content);
    }

    @Test
    void testNestedPathHead() {
        String bucket = "abc";
        String key = "sss/FAQ_zh.md";
        String content = "# FAQ\nThis is a test file";

        // First upload the file
        webTestClient
                .put()
                .uri("/" + bucket + "/" + key) // Use string concatenation
                .headers(headers -> {
                    headers.add("Authorization", "Basic bWluaW9hZG1pbjptaW5pb2FkbWlu"); // minioadmin:minioadmin
                    headers.add("Content-Type", "text/markdown");
                })
                .bodyValue(content)
                .exchange()
                .expectStatus().isCreated();

        // Wait a bit for the operation to complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Now try to HEAD the file with nested path
        webTestClient
                .head()
                .uri("/" + bucket + "/" + key) // Use string concatenation
                .headers(headers -> {
                    headers.add("Authorization", "Basic bWluaW9hZG1pbjptaW5pb2FkbWlu"); // minioadmin:minioadmin
                })
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("Content-Length")
                .expectHeader().exists("Last-Modified");
    }

    @Test
    void testNestedPathDelete() {
        String bucket = "abc";
        String key = "sss/FAQ_zh.md";
        String content = "# FAQ\nThis is a test file";

        // First upload the file
        webTestClient
                .put()
                .uri("/" + bucket + "/" + key) // Use string concatenation
                .headers(headers -> {
                    headers.add("Authorization", "Basic bWluaW9hZG1pbjptaW5pb2FkbWlu"); // minioadmin:minioadmin
                    headers.add("Content-Type", "text/markdown");
                })
                .bodyValue(content)
                .exchange()
                .expectStatus().isCreated();

        // Wait a bit for the operation to complete
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Now try to delete the file with nested path
        webTestClient
                .delete()
                .uri("/" + bucket + "/" + key) // Use string concatenation
                .headers(headers -> {
                    headers.add("Authorization", "Basic bWluaW9hZG1pbjptaW5pb2FkbWlu"); // minioadmin:minioadmin
                })
                .exchange()
                .expectStatus().isNoContent();

        // Verify the file is deleted by trying to get it (should return 404)
        webTestClient
                .get()
                .uri("/" + bucket + "/" + key) // Use string concatenation
                .headers(headers -> {
                    headers.add("Authorization", "Basic bWluaW9hZG1pbjptaW5pb2FkbWlu"); // minioadmin:minioadmin
                })
                .exchange()
                .expectStatus().isNotFound();
    }
}