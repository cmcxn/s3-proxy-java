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
 * Test for object listing functionality with deduplication service.
 * Tests the scenario described in the problem statement where files stored through 
 * deduplication service should be properly listed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class DeduplicationListingTest {
    
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
    void testListObjectsWithDeduplication() throws Exception {
        // Test the scenario from problem statement:
        // Upload file to gt/docqa/local_file/UID_yanfei/KB_123456/f3aeda1b3c834859862dda455c0a1a01/FAQ_zh.md
        // and then list with prefix gt/docqa/local_file/UID_yanfei/KB_123456/f3aeda1b3c834859862dda455c0a1a01/
        
        String testContent = "# FAQ\n\nThis is a frequently asked questions file.";
        String testKey = "gt/docqa/local_file/UID_yanfei/KB_123456/f3aeda1b3c834859862dda455c0a1a01/FAQ_zh.md";
        String listPrefix = "gt/docqa/local_file/UID_yanfei/KB_123456/f3aeda1b3c834859862dda455c0a1a01/";
        
        // Basic auth header
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
        
        // 2. List objects with the prefix - this should return the file
        webTestClient.get()
                .uri("/" + testBucket + "?prefix=" + listPrefix)
                .header("Authorization", authHeader)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/xml")
                .expectBody(String.class)
                .value(body -> {
                    System.out.println("List response: " + body);
                    // Should contain the uploaded file
                    assert body.contains("<Key>" + testKey + "</Key>") : "Response should contain the uploaded file key";
                    assert body.contains("<Prefix>" + listPrefix + "</Prefix>") : "Response should contain the requested prefix";
                    assert !body.contains("Successfully listed 0 objects") : "Should not list 0 objects";
                    // Verify it's S3-compatible XML
                    assert body.contains("ListBucketResult") : "Should be valid S3 XML response";
                    assert body.contains("<Contents>") : "Should contain Contents element";
                    assert body.contains("<Size>") : "Should contain Size element";
                    assert body.contains("<ETag>") : "Should contain ETag element";
                });
        
        // 3. Verify the file can be retrieved
        webTestClient.get()
                .uri("/" + testBucket + "/" + testKey)
                .header("Authorization", authHeader)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/markdown")
                .expectBody(String.class)
                .value(content -> {
                    assert testContent.equals(content) : "Retrieved content should match uploaded content";
                });
    }

    @Test
    void testListObjectsEmptyPrefix() throws Exception {
        // Test listing without prefix after uploading files
        String testContent = "Test content";
        String testKey1 = "folder1/file1.txt";
        String testKey2 = "folder2/file2.txt";
        
        String credentials = "minioadmin:minioadmin";
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        String authHeader = "Basic " + encodedCredentials;
        
        // Upload two files
        webTestClient.put()
                .uri("/" + testBucket + "/" + testKey1)
                .header("Authorization", authHeader)
                .header("Content-Type", "text/plain")
                .bodyValue(testContent)
                .exchange()
                .expectStatus().isCreated();
        
        webTestClient.put()
                .uri("/" + testBucket + "/" + testKey2)
                .header("Authorization", authHeader)
                .header("Content-Type", "text/plain")
                .bodyValue(testContent)
                .exchange()
                .expectStatus().isCreated();
        
        // List all objects in bucket
        webTestClient.get()
                .uri("/" + testBucket)
                .header("Authorization", authHeader)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/xml")
                .expectBody(String.class)
                .value(body -> {
                    System.out.println("List all response: " + body);
                    // Should contain both files
                    assert body.contains("<Key>" + testKey1 + "</Key>") : "Should contain first file";
                    assert body.contains("<Key>" + testKey2 + "</Key>") : "Should contain second file";
                    assert body.contains("<Prefix></Prefix>") : "Should have empty prefix";
                });
    }

    @Test 
    void testListObjectsWithDelimiter() throws Exception {
        // Test directory-style listing with delimiter
        String testContent = "Test content";
        String credentials = "minioadmin:minioadmin";
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        String authHeader = "Basic " + encodedCredentials;
        
        // Upload files in nested structure
        webTestClient.put()
                .uri("/" + testBucket + "/myfolder/file1.txt")
                .header("Authorization", authHeader)
                .header("Content-Type", "text/plain")
                .bodyValue(testContent)
                .exchange()
                .expectStatus().isCreated();
                
        webTestClient.put()
                .uri("/" + testBucket + "/myfolder/subfolder/file2.txt")
                .header("Authorization", authHeader)
                .header("Content-Type", "text/plain")
                .bodyValue(testContent)
                .exchange()
                .expectStatus().isCreated();
        
        // List with delimiter to get directory-style view
        webTestClient.get()
                .uri("/" + testBucket + "?prefix=myfolder/&delimiter=/")
                .header("Authorization", authHeader)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/xml")
                .expectBody(String.class)
                .value(body -> {
                    System.out.println("Delimiter response: " + body);
                    // Should contain direct file and common prefix for subfolder
                    assert body.contains("<Key>myfolder/file1.txt</Key>") : "Should contain direct file";
                    assert body.contains("<CommonPrefixes>") : "Should have common prefixes";
                    assert body.contains("<Prefix>myfolder/subfolder/</Prefix>") : "Should show subfolder as common prefix";
                });
    }
}