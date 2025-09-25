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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test class demonstrates how to use standard HTTP clients (like RestTemplate, curl, etc.)
 * to interact with files through the S3 Proxy service, simulating real-world usage.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class HttpClientProxyTest {

    @Container
    static GenericContainer<?> minioContainer = new GenericContainer<>("minio/minio:RELEASE.2023-09-04T19-57-37Z")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "testkey")
            .withEnv("MINIO_ROOT_PASSWORD", "testsecret")
            .withCommand("server", "/data");

    @LocalServerPort
    private int port;

    private RestTemplate restTemplate;
    private MinioClient directMinioClient;
    private String proxyBaseUrl;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("MINIO_ENDPOINT", () -> "http://localhost:" + minioContainer.getMappedPort(9000));
        registry.add("MINIO_ACCESS_KEY", () -> "testkey");
        registry.add("MINIO_SECRET_KEY", () -> "testsecret");
    }

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = new RestTemplate();
        proxyBaseUrl = "http://localhost:" + port;
        
        // Client for direct Minio access (to verify operations)
        directMinioClient = MinioClient.builder()
                .endpoint("http://localhost:" + minioContainer.getMappedPort(9000))
                .credentials("testkey", "testsecret")
                .build();

        // Ensure test bucket exists
        String bucket = "test-bucket";
        if (!directMinioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            directMinioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    @Test
    void testUploadFileUsingHttpClient() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "http-client/test-file.txt";
        String content = "This file was uploaded using HTTP client through the proxy!";

        // Use RestTemplate to upload through our proxy (like curl would)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        HttpEntity<String> request = new HttpEntity<>(content, headers);
        
        ResponseEntity<Void> response = restTemplate.exchange(
                proxyBaseUrl + "/proxy/{bucket}/{key}",
                HttpMethod.PUT,
                request,
                Void.class,
                bucket,
                objectKey
        );

        assertThat(response.getStatusCodeValue()).isEqualTo(201);

        // Verify the file exists in the actual Minio instance
        StatObjectResponse stat = directMinioClient.statObject(
                StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
        assertThat(stat.size()).isEqualTo(content.length());
        assertThat(stat.contentType()).isEqualTo("text/plain; charset=UTF-8");

        // Read back the content directly from Minio to verify
        try (GetObjectResponse minioResponse = directMinioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            String retrievedContent = new String(minioResponse.readAllBytes());
            assertThat(retrievedContent).isEqualTo(content);
        }
    }

    @Test
    void testDownloadFileUsingHttpClient() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "http-client/download-test.txt";
        String content = "This file will be downloaded using HTTP client through the proxy!";

        // First upload file directly to Minio
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes())) {
            directMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, content.length(), -1)
                    .contentType("text/plain")
                    .build());
        }

        // Use RestTemplate to download through our proxy (like curl would)
        ResponseEntity<String> response = restTemplate.exchange(
                proxyBaseUrl + "/proxy/{bucket}/{key}",
                HttpMethod.GET,
                null,
                String.class,
                bucket,
                objectKey
        );

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(content);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
    }

    @Test
    void testDeleteFileUsingHttpClient() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "http-client/delete-test.txt";
        String content = "This file will be deleted using HTTP client through the proxy!";

        // First upload file directly to Minio
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes())) {
            directMinioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, content.length(), -1)
                    .build());
        }

        // Verify file exists before deletion
        assertTrue(objectExists(directMinioClient, bucket, objectKey));

        // Use RestTemplate to delete through our proxy (like curl would)
        ResponseEntity<Void> response = restTemplate.exchange(
                proxyBaseUrl + "/proxy/{bucket}/{key}",
                HttpMethod.DELETE,
                null,
                Void.class,
                bucket,
                objectKey
        );

        assertThat(response.getStatusCodeValue()).isEqualTo(204);

        // Verify file was deleted from actual Minio
        assertThat(objectExists(directMinioClient, bucket, objectKey)).isFalse();
    }

    @Test
    void testPresignedUrlGeneration() {
        String bucket = "test-bucket";
        String objectKey = "http-client/presigned-test.txt";

        // Use RestTemplate to get presigned URL (like curl would)
        ResponseEntity<Map> response = restTemplate.exchange(
                proxyBaseUrl + "/proxy/presign/{bucket}/{key}?method=GET&expiry=300",
                HttpMethod.GET,
                null,
                Map.class,
                bucket,
                objectKey
        );

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, String> result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result).containsKeys("url", "method");
        assertThat(result.get("method")).isEqualTo("GET");
        assertThat(result.get("url")).contains(bucket).contains(objectKey);
    }

    @Test
    void testBinaryFileUploadDownload() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "http-client/binary-file.bin";
        
        // Create binary content with all possible byte values
        byte[] binaryContent = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryContent[i] = (byte) i;
        }

        // Upload binary file through proxy
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        HttpEntity<byte[]> request = new HttpEntity<>(binaryContent, headers);
        
        ResponseEntity<Void> uploadResponse = restTemplate.exchange(
                proxyBaseUrl + "/proxy/{bucket}/{key}",
                HttpMethod.PUT,
                request,
                Void.class,
                bucket,
                objectKey
        );

        assertThat(uploadResponse.getStatusCodeValue()).isEqualTo(201);

        // Download and verify binary content
        ResponseEntity<byte[]> downloadResponse = restTemplate.exchange(
                proxyBaseUrl + "/proxy/{bucket}/{key}",
                HttpMethod.GET,
                null,
                byte[].class,
                bucket,
                objectKey
        );

        assertThat(downloadResponse.getStatusCodeValue()).isEqualTo(200);
        assertThat(downloadResponse.getBody()).isEqualTo(binaryContent);
        assertThat(downloadResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);

        // Clean up
        directMinioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());
    }

    @Test
    void testLargeFileUploadDownload() throws Exception {
        String bucket = "test-bucket";
        String objectKey = "http-client/large-file.dat";
        
        // Create a 1MB file with pattern data (smaller than previous test for faster execution)
        byte[] largeContent = new byte[1024 * 1024]; // 1MB
        for (int i = 0; i < largeContent.length; i++) {
            largeContent[i] = (byte) (i % 256);
        }

        // Upload large file through proxy
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        HttpEntity<byte[]> request = new HttpEntity<>(largeContent, headers);
        
        ResponseEntity<Void> uploadResponse = restTemplate.exchange(
                proxyBaseUrl + "/proxy/{bucket}/{key}",
                HttpMethod.PUT,
                request,
                Void.class,
                bucket,
                objectKey
        );

        assertThat(uploadResponse.getStatusCodeValue()).isEqualTo(201);

        // Verify file size in actual Minio
        StatObjectResponse stat = directMinioClient.statObject(
                StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
        assertThat(stat.size()).isEqualTo(largeContent.length);

        // Download and verify content through proxy
        ResponseEntity<byte[]> downloadResponse = restTemplate.exchange(
                proxyBaseUrl + "/proxy/{bucket}/{key}",
                HttpMethod.GET,
                null,
                byte[].class,
                bucket,
                objectKey
        );

        assertThat(downloadResponse.getStatusCodeValue()).isEqualTo(200);
        assertThat(downloadResponse.getBody()).isEqualTo(largeContent);

        // Clean up
        directMinioClient.removeObject(RemoveObjectArgs.builder()
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