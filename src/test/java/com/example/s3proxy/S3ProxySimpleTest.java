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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
public class S3ProxySimpleTest {

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

    @BeforeEach
    void setUp() throws Exception {
        // Create bucket if it doesn't exist
        boolean bucketExists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(TEST_BUCKET).build());
        if (!bucketExists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(TEST_BUCKET).build());
        }
    }

    @Test
    void testSimpleFileOperations() throws Exception {
        String simpleKey = "simple-file.txt";
        String content = "Hello, S3 Proxy World!";

        // Test uploading a file
        webTestClient.put()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, simpleKey)
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue(content)
                .exchange()
                .expectStatus().isCreated();

        // Verify the file was uploaded to MinIO
        StatObjectResponse stat = minioClient.statObject(
                StatObjectArgs.builder().bucket(TEST_BUCKET).object(simpleKey).build());
        assertThat(stat.size()).isEqualTo(content.length());

        // Test downloading the file
        webTestClient.get()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, simpleKey)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.TEXT_PLAIN)
                .expectBody(String.class)
                .isEqualTo(content);

        // Test deleting the file
        webTestClient.delete()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, simpleKey)
                .exchange()
                .expectStatus().isNoContent();

        // Verify file was deleted
        assertThat(objectExists(TEST_BUCKET, simpleKey)).isFalse();
    }

    @Test
    void testPresignedUrl() {
        String key = "presign-test.txt";

        webTestClient.get()
                .uri("/proxy/presign/{bucket}/{key}?method=GET&expiry=300", TEST_BUCKET, key)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .value(response -> {
                    assertThat(response).containsKeys("url", "method");
                    assertThat(response.get("method")).isEqualTo("GET");
                    assertThat(response.get("url")).asString().contains(TEST_BUCKET).contains(key);
                });
    }

    @Test
    void testBinaryData() throws Exception {
        String binaryKey = "binary-test.bin";
        byte[] binaryData = new byte[]{1, 2, 3, 4, 5, -128, -1, 0, 127};

        // Upload binary data
        webTestClient.put()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, binaryKey)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(binaryData)
                .exchange()
                .expectStatus().isCreated();

        // Download and verify
        webTestClient.get()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, binaryKey)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .expectBody(byte[].class)
                .isEqualTo(binaryData);

        // Clean up
        webTestClient.delete()
                .uri("/proxy/{bucket}/{key}", TEST_BUCKET, binaryKey)
                .exchange()
                .expectStatus().isNoContent();
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