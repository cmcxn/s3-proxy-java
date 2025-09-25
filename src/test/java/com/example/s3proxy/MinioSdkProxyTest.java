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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test class demonstrates how to use the Minio SDK to interact with files
 * through the S3 Proxy service, as if it were a real Minio server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class MinioSdkProxyTest {

    @Container
    static GenericContainer<?> minioContainer = new GenericContainer<>("minio/minio:RELEASE.2023-09-04T19-57-37Z")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "testkey")
            .withEnv("MINIO_ROOT_PASSWORD", "testsecret")
            .withCommand("server", "/data");

    @LocalServerPort
    private int port;

    private MinioClient proxyMinioClient;
    private MinioClient directMinioClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("MINIO_ENDPOINT", () -> "http://localhost:" + minioContainer.getMappedPort(9000));
        registry.add("MINIO_ACCESS_KEY", () -> "testkey");
        registry.add("MINIO_SECRET_KEY", () -> "testsecret");
    }

    @BeforeEach
    void setUp() {
        // Client for direct Minio access (to verify operations)
        directMinioClient = MinioClient.builder()
                .endpoint("http://localhost:" + minioContainer.getMappedPort(9000))
                .credentials("testkey", "testsecret")
                .build();

        // Client configured to use our proxy service as if it were Minio
        // Note: This simulates how a real application would use Minio SDK with our proxy
        proxyMinioClient = MinioClient.builder()
                .endpoint("http://localhost:" + port + "/proxy")
                .credentials("dummy", "dummy") // Not used by proxy, but required by SDK
                .build();
    }

    @Test
    void testUploadFileUsingMinioSdk() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "minio-sdk/test-file.txt";
        String content = "This file was uploaded using Minio SDK through the proxy!";

        // First ensure bucket exists in actual Minio
        if (!directMinioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            directMinioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }

        // Use Minio SDK to upload through our proxy
        try (InputStream inputStream = new ByteArrayInputStream(content.getBytes())) {
            proxyMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, content.length(), -1)
                    .contentType("text/plain")
                    .build());
        }

        // Verify the file exists in the actual Minio instance
        StatObjectResponse stat = directMinioClient.statObject(
                StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
        assertThat(stat.size()).isEqualTo(content.length());
        assertThat(stat.contentType()).isEqualTo("text/plain");

        // Read back the content directly from Minio to verify
        try (GetObjectResponse response = directMinioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            String retrievedContent = new String(response.readAllBytes());
            assertThat(retrievedContent).isEqualTo(content);
        }
    }

    @Test
    void testDownloadFileUsingMinioSdk() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "minio-sdk/download-test.txt";
        String content = "This file will be downloaded using Minio SDK through the proxy!";

        // First ensure bucket exists and upload file directly to Minio
        if (!directMinioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            directMinioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        
        try (InputStream inputStream = new ByteArrayInputStream(content.getBytes())) {
            directMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, content.length(), -1)
                    .contentType("text/plain")
                    .build());
        }

        // Use Minio SDK to download through our proxy
        try (GetObjectResponse response = proxyMinioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            
            String downloadedContent = new String(response.readAllBytes());
            assertThat(downloadedContent).isEqualTo(content);
            
            // Verify headers are properly passed through
            assertThat(response.headers().get("Content-Type")).isEqualTo("text/plain");
        }
    }

    @Test
    void testDeleteFileUsingMinioSdk() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "minio-sdk/delete-test.txt";
        String content = "This file will be deleted using Minio SDK through the proxy!";

        // First ensure bucket exists and upload file directly to Minio
        if (!directMinioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            directMinioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        
        try (InputStream inputStream = new ByteArrayInputStream(content.getBytes())) {
            directMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, content.length(), -1)
                    .build());
        }

        // Verify file exists before deletion
        assertTrue(objectExists(directMinioClient, bucket, objectKey));

        // Use Minio SDK to delete through our proxy
        proxyMinioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());

        // Verify file was deleted from actual Minio
        assertThat(objectExists(directMinioClient, bucket, objectKey)).isFalse();
    }

    @Test
    void testLargeFileUploadDownload() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "minio-sdk/large-file.dat";
        
        // Create a 5MB file with pattern data
        byte[] largeContent = new byte[5 * 1024 * 1024]; // 5MB
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        // Ensure bucket exists
        if (!directMinioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            directMinioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }

        // Upload large file through proxy using Minio SDK
        try (InputStream inputStream = new ByteArrayInputStream(largeContent)) {
            proxyMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, largeContent.length, -1)
                    .contentType("application/octet-stream")
                    .build());
        }

        // Verify file size in actual Minio
        StatObjectResponse stat = directMinioClient.statObject(
                StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
        assertThat(stat.size()).isEqualTo(largeContent.length);

        // Download and verify content through proxy using Minio SDK
        try (GetObjectResponse response = proxyMinioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            
            byte[] downloadedContent = response.readAllBytes();
            assertThat(downloadedContent).isEqualTo(largeContent);
        }

        // Clean up
        proxyMinioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());
    }

    @Test
    void testBinaryFileUploadDownload() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "minio-sdk/binary-file.bin";
        
        // Create binary content with all possible byte values
        byte[] binaryContent = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryContent[i] = (byte) i;
        }

        // Ensure bucket exists
        if (!directMinioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            directMinioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }

        // Upload binary file through proxy
        try (InputStream inputStream = new ByteArrayInputStream(binaryContent)) {
            proxyMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, binaryContent.length, -1)
                    .contentType("application/octet-stream")
                    .build());
        }

        // Download and verify binary content
        try (GetObjectResponse response = proxyMinioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            
            byte[] downloadedContent = response.readAllBytes();
            assertThat(downloadedContent).isEqualTo(binaryContent);
        }

        // Clean up
        proxyMinioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());
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