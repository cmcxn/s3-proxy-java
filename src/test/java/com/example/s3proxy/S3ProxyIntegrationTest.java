package com.example.s3proxy;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.MinioException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
public class S3ProxyIntegrationTest {

    @Container
    static GenericContainer<?> minioContainer = new GenericContainer<>("minio/minio:RELEASE.2023-09-04T19-57-37Z")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "testkey")
            .withEnv("MINIO_ROOT_PASSWORD", "testsecret")
            .withCommand("server", "/data");

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        MinioClient testMinioClient() {
            return MinioClient.builder()
                    .endpoint("http://localhost:" + minioContainer.getMappedPort(9000))
                    .credentials("testkey", "testsecret")
                    .build();
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MinioClient minioClient;

    private static final String TEST_BUCKET = "test-bucket";
    private static final String TEST_KEY = "test/file.txt";
    private static final String TEST_CONTENT = "Hello, S3 Proxy World!";

    @BeforeEach
    void setUp() throws Exception {
        // Create bucket if it doesn't exist
        boolean bucketExists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(TEST_BUCKET).build());
        if (!bucketExists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(TEST_BUCKET).build());
        }
    }

    @Test
    void testPutObject() throws Exception {
        // Test uploading a file through the proxy
        webTestClient.put()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, TEST_KEY)
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue(TEST_CONTENT)
                .exchange()
                .expectStatus().isCreated();

        // Verify the file was actually uploaded to MinIO
        StatObjectResponse stat = minioClient.statObject(
                StatObjectArgs.builder().bucket(TEST_BUCKET).object(TEST_KEY).build());
        assertThat(stat.size()).isEqualTo(TEST_CONTENT.length());

        // Read the content directly from MinIO to verify
        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder().bucket(TEST_BUCKET).object(TEST_KEY).build())) {
            String content = new String(response.readAllBytes());
            assertThat(content).isEqualTo(TEST_CONTENT);
        }
    }

    @Test
    void testGetObject() throws Exception {
        // First upload a file directly to MinIO
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(TEST_BUCKET)
                .object(TEST_KEY)
                .stream(new ByteArrayInputStream(TEST_CONTENT.getBytes()), TEST_CONTENT.length(), -1)
                .contentType("text/plain")
                .build());

        // Test downloading through the proxy
        webTestClient.get()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, TEST_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.TEXT_PLAIN)
                .expectBody(String.class)
                .isEqualTo(TEST_CONTENT);
    }

    @Test
    void testDeleteObject() throws Exception {
        // First upload a file directly to MinIO
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(TEST_BUCKET)
                .object(TEST_KEY)
                .stream(new ByteArrayInputStream(TEST_CONTENT.getBytes()), TEST_CONTENT.length(), -1)
                .build());

        // Verify file exists
        assertTrue(objectExists(TEST_BUCKET, TEST_KEY));

        // Test deleting through the proxy
        webTestClient.delete()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, TEST_KEY)
                .exchange()
                .expectStatus().isNoContent();

        // Verify file was deleted from MinIO
        assertFalse(objectExists(TEST_BUCKET, TEST_KEY));
    }

    @Test
    void testPresignedUrl() {
        // Test generating presigned URLs
        webTestClient.get()
                .uri("/proxy/presign/{bucket}/{key}?method=GET&expiry=300", TEST_BUCKET, TEST_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .value(response -> {
                    assertThat(response).containsKeys("url", "method");
                    assertThat(response.get("method")).isEqualTo("GET");
                    assertThat(response.get("url")).asString().contains(TEST_BUCKET).contains(TEST_KEY);
                });

        // Test presigned URL for PUT
        webTestClient.get()
                .uri("/proxy/presign/{bucket}/{key}?method=PUT&expiry=600", TEST_BUCKET, "upload/file.txt")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .value(response -> {
                    assertThat(response).containsKeys("url", "method");
                    assertThat(response.get("method")).isEqualTo("PUT");
                    assertThat(response.get("url")).asString().contains(TEST_BUCKET).contains("upload/file.txt");
                });
    }

    @Test
    void testUploadAndDownloadBinaryData() throws Exception {
        byte[] binaryData = new byte[]{1, 2, 3, 4, 5, -128, -1, 0, 127};
        String binaryKey = "binary/test.bin";

        // Upload binary data through proxy
        webTestClient.put()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, binaryKey)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(binaryData)
                .exchange()
                .expectStatus().isCreated();

        // Download binary data through proxy and verify
        webTestClient.get()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, binaryKey)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .expectBody(byte[].class)
                .isEqualTo(binaryData);
    }

    @Test
    void testNonExistentObject() {
        // Test getting a non-existent object
        webTestClient.get()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, "non-existent/file.txt")
                .exchange()
                .expectStatus().is5xxServerError(); // Should return an error
    }

    @Test
    void testLargeFile() throws Exception {
        // Test with a larger file (1MB)
        byte[] largeData = new byte[1024 * 1024]; // 1MB
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        String largeKey = "large/test-file.dat";

        // Upload large file through proxy
        webTestClient.put()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, largeKey)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(largeData)
                .exchange()
                .expectStatus().isCreated();

        // Verify file size in MinIO
        StatObjectResponse stat = minioClient.statObject(
                StatObjectArgs.builder().bucket(TEST_BUCKET).object(largeKey).build());
        assertThat(stat.size()).isEqualTo(largeData.length);

        // Download and verify content
        webTestClient.get()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, largeKey)
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .isEqualTo(largeData);

        // Clean up
        minioClient.removeObject(RemoveObjectArgs.builder().bucket(TEST_BUCKET).object(largeKey).build());
    }

    private boolean objectExists(String bucket, String object) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(object).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}