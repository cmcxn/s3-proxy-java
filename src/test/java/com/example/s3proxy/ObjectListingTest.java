package com.example.s3proxy;

import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Test for object listing functionality.
 * Tests the proxy's ability to list objects from MinIO and return S3-compatible XML responses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class ObjectListingTest {
    
    @Container
    static GenericContainer<?> minioContainer = new GenericContainer<>("minio/minio:latest")
            .withExposedPorts(9000)
            .withEnv("MINIO_ACCESS_KEY", "minioadmin")
            .withEnv("MINIO_SECRET_KEY", "minioadmin")
            .withCommand("server", "/data");

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;
    private MinioClient directMinioClient;
    private final String testBucket = "test-bucket";

    @BeforeEach
    void setUp() throws Exception {
        // Set up environment variables for the application to connect to test MinIO
        System.setProperty("MINIO_ENDPOINT", "http://localhost:" + minioContainer.getMappedPort(9000));
        System.setProperty("MINIO_ACCESS_KEY", "minioadmin");
        System.setProperty("MINIO_SECRET_KEY", "minioadmin");
        
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // Set up direct MinIO client for test setup
        directMinioClient = MinioClient.builder()
                .endpoint("http://localhost:" + minioContainer.getMappedPort(9000))
                .credentials("minioadmin", "minioadmin")
                .build();

        // Create test bucket if it doesn't exist
        if (!directMinioClient.bucketExists(BucketExistsArgs.builder().bucket(testBucket).build())) {
            directMinioClient.makeBucket(MakeBucketArgs.builder().bucket(testBucket).build());
        }
    }

    @Test
    void testListObjectsEmpty() throws Exception {
        // Test listing objects in an empty bucket
        webTestClient.get()
                .uri("/" + testBucket)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/xml")
                .expectBody(String.class)
                .value(body -> {
                    // Should contain ListBucketResult XML structure
                    assert body.contains("<ListBucketResult");
                    assert body.contains("<Name>" + testBucket + "</Name>");
                    assert body.contains("<IsTruncated>false</IsTruncated>");
                });
    }

    @Test
    void testListObjectsWithPrefix() throws Exception {
        // Create test objects with different prefixes
        String content = "test content";
        ByteArrayInputStream stream1 = new ByteArrayInputStream(content.getBytes());
        ByteArrayInputStream stream2 = new ByteArrayInputStream(content.getBytes());
        ByteArrayInputStream stream3 = new ByteArrayInputStream(content.getBytes());
        
        directMinioClient.putObject(PutObjectArgs.builder()
                .bucket(testBucket)
                .object("myfolder/file1.txt")
                .stream(stream1, content.length(), -1)
                .contentType("text/plain")
                .build());
                
        directMinioClient.putObject(PutObjectArgs.builder()
                .bucket(testBucket)
                .object("myfolder/file2.txt")
                .stream(stream2, content.length(), -1)
                .contentType("text/plain")
                .build());
                
        directMinioClient.putObject(PutObjectArgs.builder()
                .bucket(testBucket)
                .object("otherfolder/file3.txt")
                .stream(stream3, content.length(), -1)
                .contentType("text/plain")
                .build());

        // Test listing with prefix
        webTestClient.get()
                .uri("/" + testBucket + "?prefix=myfolder/")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/xml")
                .expectBody(String.class)
                .value(body -> {
                    System.out.println("Response body: " + body);
                    // Should contain the two files in myfolder but not otherfolder
                    assert body.contains("<Key>myfolder/file1.txt</Key>");
                    assert body.contains("<Key>myfolder/file2.txt</Key>");
                    assert !body.contains("<Key>otherfolder/file3.txt</Key>");
                    assert body.contains("<Prefix>myfolder/</Prefix>");
                });
    }

    @Test
    void testListObjectsCompatibilityWithMinioClient() throws Exception {
        // Test that the proxy's list objects response is compatible
        // with what MinIO SDK expects by making a direct comparison
        String content = "test content for compatibility";
        ByteArrayInputStream stream = new ByteArrayInputStream(content.getBytes());
        
        directMinioClient.putObject(PutObjectArgs.builder()
                .bucket(testBucket)
                .object("compatibility-test.txt")
                .stream(stream, content.length(), -1)
                .contentType("text/plain")
                .build());

        // List objects using direct MinIO client
        Iterable<Result<Item>> directResults = directMinioClient.listObjects(
            ListObjectsArgs.builder().bucket(testBucket).build());
        
        List<String> directObjectNames = new ArrayList<>();
        for (Result<Item> result : directResults) {
            directObjectNames.add(result.get().objectName());
        }

        // List objects using the proxy
        webTestClient.get()
                .uri("/" + testBucket)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/xml")
                .expectBody(String.class)
                .value(body -> {
                    // Verify the response contains the same objects
                    for (String objectName : directObjectNames) {
                        assert body.contains("<Key>" + objectName + "</Key>");
                    }
                });
    }
}