package com.example.s3proxy;

import com.example.s3proxy.service.BucketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BucketLifecycleIntegrationTest {

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
    private BucketService bucketService;

    private static final String BASIC_AUTH_HEADER = "Basic bWluaW9hZG1pbjptaW5pb2FkbWlu"; // minioadmin:minioadmin

    @Test
    void bucketCreationEndpointSupportsMcMirrorScenario() {
        String bucketName = "mirror-" + UUID.randomUUID();
        String objectKey = "path/to/file.txt";
        String content = "mirror test data";

        // Initially the bucket should not exist
        webTestClient.head()
                .uri("/" + bucketName)
                .header("Authorization", BASIC_AUTH_HEADER)
                .exchange()
                .expectStatus().isNotFound();

        // mc mirror issues a list request with a trailing slash before attempting to create or upload
        webTestClient.get()
                .uri(builder -> builder
                        .path("/" + bucketName + "/")
                        .queryParam("list-type", "2")
                        .queryParam("max-keys", "1000")
                        .build())
                .header("Authorization", BASIC_AUTH_HEADER)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_XML)
                .expectBody(String.class)
                .value(body -> Assertions.assertTrue(body.contains("<ListBucketResult")));

        // Listing creates the logical bucket so subsequent HEAD requests succeed
        webTestClient.head()
                .uri("/" + bucketName)
                .header("Authorization", BASIC_AUTH_HEADER)
                .exchange()
                .expectStatus().isOk();
        Assertions.assertTrue(bucketService.bucketExists(bucketName));

        // Create bucket through S3 API (explicit call still supported)
        webTestClient.put()
                .uri("/" + bucketName)
                .header("Authorization", BASIC_AUTH_HEADER)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        // After creation HEAD should succeed
        webTestClient.head()
                .uri("/" + bucketName)
                .header("Authorization", BASIC_AUTH_HEADER)
                .exchange()
                .expectStatus().isOk();

        // Uploading an object should also succeed and keep the bucket registered
        webTestClient.put()
                .uri("/" + bucketName + "/" + objectKey)
                .header("Authorization", BASIC_AUTH_HEADER)
                .header("Content-Type", "text/plain")
                .bodyValue(content)
                .exchange()
                .expectStatus().isCreated();

        // The bucket is now known by the bucket service
        Assertions.assertTrue(bucketService.bucketExists(bucketName));
    }
}
