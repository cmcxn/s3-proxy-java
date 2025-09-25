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
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.http.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * This test class tests MinIO SDK directly against the S3 Proxy service,
 * verifying if the proxy can act as a compatible MinIO/S3 server when accessed via MinIO SDK.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class MinioSdkDirectProxyTest {

    @Container
    static GenericContainer<?> minioContainer = new GenericContainer<>("minio/minio:RELEASE.2023-09-04T19-57-37Z")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "testkey")
            .withEnv("MINIO_ROOT_PASSWORD", "testsecret")
            .withCommand("server", "/data");

    @LocalServerPort
    private int port;

    private MinioClient proxyMinioClient; // MinIO SDK client pointing to proxy service
    private MinioClient directMinioClient; // MinIO SDK client pointing to real MinIO (for verification)

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("MINIO_ENDPOINT", () -> "http://localhost:" + minioContainer.getMappedPort(9000));
        registry.add("MINIO_ACCESS_KEY", () -> "testkey");
        registry.add("MINIO_SECRET_KEY", () -> "testsecret");
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public MinioClient testMinioClient() {
            return MinioClient.builder()
                    .endpoint("http://localhost:" + minioContainer.getMappedPort(9000))
                    .credentials("testkey", "testsecret")
                    .build();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // Client pointing to the proxy service (this is what we're testing)
        proxyMinioClient = MinioClient.builder()
                .endpoint("http://localhost:" + port + "/proxy")
                .credentials("testkey", "testsecret")
                .build();

        // Client pointing to real MinIO (for verification and setup)
        directMinioClient = MinioClient.builder()
                .endpoint("http://localhost:" + minioContainer.getMappedPort(9000))
                .credentials("testkey", "testsecret")
                .build();

        // Ensure test bucket exists in real MinIO
        String bucket = "test-bucket";
        if (!directMinioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            directMinioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    @Test
    void testPutObjectThroughMinioSdk() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "minio-sdk/put-test.txt";
        String content = "This file was uploaded using MinIO SDK through the proxy!";

        // Upload using MinIO SDK pointing to proxy
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes())) {
            proxyMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, content.length(), -1)
                    .contentType("text/plain")
                    .build());
        }

        // Verify the file exists in the actual MinIO instance
        StatObjectResponse stat = directMinioClient.statObject(
                StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
        assertThat(stat.size()).isEqualTo(content.length());

        // Read back content from real MinIO to verify
        try (GetObjectResponse response = directMinioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            String retrievedContent = new String(response.readAllBytes());
            assertThat(retrievedContent).isEqualTo(content);
        }

        // Clean up
        directMinioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());
    }

    @Test
    void testGetObjectThroughMinioSdk() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "minio-sdk/get-test.txt";
        String content = "This file will be downloaded using MinIO SDK through the proxy!";

        // First upload file directly to MinIO
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes())) {
            directMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, content.length(), -1)
                    .contentType("text/plain")
                    .build());
        }

        // Download using MinIO SDK pointing to proxy
        try (GetObjectResponse response = proxyMinioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            String retrievedContent = new String(response.readAllBytes());
            assertThat(retrievedContent).isEqualTo(content);
        }

        // Clean up
        directMinioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());
    }

    @Test
    void testDeleteObjectThroughMinioSdk() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "minio-sdk/delete-test.txt";
        String content = "This file will be deleted using MinIO SDK through the proxy!";

        // First upload file directly to MinIO
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes())) {
            directMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, content.length(), -1)
                    .build());
        }

        // Verify file exists
        assertTrue(objectExists(directMinioClient, bucket, objectKey));

        // Delete using MinIO SDK pointing to proxy
        proxyMinioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());

        // Verify file was deleted from actual MinIO
        assertThat(objectExists(directMinioClient, bucket, objectKey)).isFalse();
    }

    @Test
    void testStatObjectThroughMinioSdk() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "minio-sdk/stat-test.txt";
        String content = "This file will be inspected using MinIO SDK through the proxy!";

        // First upload file directly to MinIO
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes())) {
            directMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, content.length(), -1)
                    .contentType("text/plain")
                    .build());
        }

        // Get object stats using MinIO SDK pointing to proxy
        StatObjectResponse stat = proxyMinioClient.statObject(
                StatObjectArgs.builder().bucket(bucket).object(objectKey).build());

        assertThat(stat.size()).isEqualTo(content.length());
        assertThat(stat.object()).isEqualTo(objectKey);
        assertThat(stat.bucket()).isEqualTo(bucket);

        // Clean up
        directMinioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());
    }

    @Test
    void testPresignedUrlThroughMinioSdk() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "minio-sdk/presigned-test.txt";

        // Generate presigned URL using MinIO SDK pointing to proxy
        String presignedUrl = proxyMinioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucket)
                        .object(objectKey)
                        .expiry(600)
                        .build());

        assertThat(presignedUrl).isNotNull();
        assertThat(presignedUrl).contains(bucket);
        assertThat(presignedUrl).contains(objectKey);
        // Should contain the proxy endpoint, not direct MinIO endpoint
        assertThat(presignedUrl).contains("localhost:" + port);
    }

    @Test
    void testBinaryDataThroughMinioSdk() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "minio-sdk/binary-test.bin";
        
        // Create binary content with all possible byte values
        byte[] binaryContent = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryContent[i] = (byte) i;
        }

        // Upload binary data using MinIO SDK pointing to proxy
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(binaryContent)) {
            proxyMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, binaryContent.length, -1)
                    .contentType("application/octet-stream")
                    .build());
        }

        // Download and verify using MinIO SDK pointing to proxy
        try (GetObjectResponse response = proxyMinioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            byte[] retrievedContent = response.readAllBytes();
            assertThat(retrievedContent).isEqualTo(binaryContent);
        }

        // Clean up
        directMinioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());
    }

    @Test
    void testBucketOperationsThroughMinioSdk() {
        String bucket = "test-new-bucket";

        // Test bucket operations - these might fail since proxy doesn't implement bucket management
        // We expect these to fail, demonstrating limitations
        assertThrows(Exception.class, () -> {
            proxyMinioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        });

        assertThrows(Exception.class, () -> {
            proxyMinioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        });
    }

    private boolean objectExists(MinioClient client, String bucket, String object) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(object).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}