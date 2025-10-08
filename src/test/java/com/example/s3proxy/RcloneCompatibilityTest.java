package com.example.s3proxy;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test that exercises the S3 ListObjects V2 flow used by rclone when mounting buckets.
 * rclone relies on list-type=2 semantics with continuation tokens, so this test ensures our
 * controller responds with the expected XML shape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class RcloneCompatibilityTest {

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
    private final String bucket = "rclone-bucket";
    private final String authHeader = "Basic " + Base64.getEncoder()
            .encodeToString("minioadmin:minioadmin".getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() throws Exception {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        directMinioClient = MinioClient.builder()
                .endpoint("http://localhost:" + minioContainer.getMappedPort(9000))
                .credentials("minioadmin", "minioadmin")
                .build();

        if (!directMinioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            directMinioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }

        String dedupeBucket = "dedupe-storage";
        if (!directMinioClient.bucketExists(BucketExistsArgs.builder().bucket(dedupeBucket).build())) {
            directMinioClient.makeBucket(MakeBucketArgs.builder().bucket(dedupeBucket).build());
        }
    }

    @Test
    void testListType2PaginationFlow() {
        // Upload objects through the proxy so that deduplication metadata is created
        upload("folder/sub/file1.txt", "file-one");
        upload("folder/sub/file2.txt", "file-two");
        upload("other.txt", "file-three");

        // Top-level listing with delimiter should behave like rclone expects when discovering directories
        String topLevelResponse = webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/" + bucket)
                        .queryParam("list-type", "2")
                        .queryParam("delimiter", "/")
                        .build())
                .header("Authorization", authHeader)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(topLevelResponse);
        assertTrue(topLevelResponse.contains("<KeyCount>1</KeyCount>"));
        assertTrue(topLevelResponse.contains("<CommonPrefixes>\n    <Prefix>folder/</Prefix>\n  </CommonPrefixes>"));
        assertFalse(topLevelResponse.contains("<NextContinuationToken>"));

        // Paginated listing under the folder prefix, just like rclone performs with continuation tokens
        String firstPage = webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/" + bucket)
                        .queryParam("list-type", "2")
                        .queryParam("prefix", "folder/")
                        .queryParam("max-keys", "1")
                        .build())
                .header("Authorization", authHeader)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(firstPage);
        assertTrue(firstPage.contains("<KeyCount>1</KeyCount>"));
        assertTrue(firstPage.contains("<IsTruncated>true</IsTruncated>"));
        assertTrue(firstPage.contains("<NextContinuationToken>folder/sub/file1.txt</NextContinuationToken>"));
        assertTrue(firstPage.contains("<Key>folder/sub/file1.txt</Key>"));

        // Fetch the next page using the continuation token returned above
        String secondPage = webTestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/" + bucket)
                        .queryParam("list-type", "2")
                        .queryParam("prefix", "folder/")
                        .queryParam("continuation-token", "folder/sub/file1.txt")
                        .build())
                .header("Authorization", authHeader)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(secondPage);
        assertTrue(secondPage.contains("<KeyCount>1</KeyCount>"));
        assertTrue(secondPage.contains("<Key>folder/sub/file2.txt</Key>"));
        assertTrue(secondPage.contains("<ContinuationToken>folder/sub/file1.txt</ContinuationToken>"));
        assertTrue(secondPage.contains("<IsTruncated>false</IsTruncated>"));
        assertFalse(secondPage.contains("<NextContinuationToken>"));
    }

    @Test
    void testMetadataRoundTripAndCopyObject() {
        String objectKey = "meta/data.txt";
        String initialMtime = "1700000000.000000000";
        String updatedMtime = "1700001000.500000000";

        webTestClient.put()
                .uri("/" + bucket + "/" + objectKey)
                .header("Authorization", authHeader)
                .header("Content-Type", "text/plain")
                .header("x-amz-meta-mtime", initialMtime)
                .header("x-amz-meta-mode", "33188")
                .bodyValue("hello metadata")
                .exchange()
                .expectStatus().isCreated();

        webTestClient.head()
                .uri("/" + bucket + "/" + objectKey)
                .header("Authorization", authHeader)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("x-amz-meta-mtime", initialMtime);

        String copyResult = webTestClient.put()
                .uri("/" + bucket + "/" + objectKey)
                .header("Authorization", authHeader)
                .header("x-amz-copy-source", "/" + bucket + "/" + objectKey)
                .header("x-amz-metadata-directive", "REPLACE")
                .header("x-amz-meta-mtime", updatedMtime)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(copyResult);
        assertTrue(copyResult.contains("<CopyObjectResult>"));
        assertTrue(copyResult.contains("<ETag>\""));

        webTestClient.head()
                .uri("/" + bucket + "/" + objectKey)
                .header("Authorization", authHeader)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("x-amz-meta-mtime", updatedMtime)
                .expectHeader().exists(HttpHeaders.LAST_MODIFIED);
    }

    private void upload(String objectKey, String body) {
        webTestClient.put()
                .uri("/" + bucket + "/" + objectKey)
                .header("Authorization", authHeader)
                .header("Content-Type", "text/plain")
                .bodyValue(body.getBytes(StandardCharsets.UTF_8))
                .exchange()
                .expectStatus().isCreated();
    }
}

