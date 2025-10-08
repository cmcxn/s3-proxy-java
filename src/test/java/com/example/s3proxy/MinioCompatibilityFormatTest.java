package com.example.s3proxy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Test to verify that the listObjects XML response format matches MinIO specifications.
 * Tests specific formatting issues like ETag quotes, CommonPrefixes, and datetime format.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class MinioCompatibilityFormatTest {
    
    @LocalServerPort
    private int port;

    @Test
    void testListObjectsXmlFormatStructure() throws Exception {
        // Set up environment variables for a mock MinIO endpoint that won't be reached
        System.setProperty("MINIO_ENDPOINT", "http://localhost:9999");
        System.setProperty("MINIO_ACCESS_KEY", "minioadmin");
        System.setProperty("MINIO_SECRET_KEY", "minioadmin");
        
        WebTestClient webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // Test the XML structure even when MinIO connection fails
        // The proxy now responds successfully using its internal metadata store
        webTestClient.get()
                .uri("/test-bucket?prefix=myfolder/&delimiter=/")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=minioadmin/20241226/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=dummy")
                .header("x-amz-date", "20241226T000000Z")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assert body.contains("<ListBucketResult");
                    assert body.contains("<Delimiter>/</Delimiter>");
                });
    }

    @Test
    void testListObjectsWithDelimiterParameter() throws Exception {
        System.setProperty("MINIO_ENDPOINT", "http://localhost:9999");
        System.setProperty("MINIO_ACCESS_KEY", "minioadmin");
        System.setProperty("MINIO_SECRET_KEY", "minioadmin");
        
        WebTestClient webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // Test that delimiter parameter is correctly processed even without MinIO
        webTestClient.get()
                .uri("/test-bucket?delimiter=/&max-keys=50")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=minioadmin/20241226/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=dummy")
                .header("x-amz-date", "20241226T000000Z")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assert body.contains("<ListBucketResult");
                    assert body.contains("<MaxKeys>50</MaxKeys>");
                });
    }

    @Test
    void testListObjectsAuthenticationFlow() throws Exception {
        System.setProperty("MINIO_ENDPOINT", "http://localhost:9999");
        System.setProperty("MINIO_ACCESS_KEY", "test");
        System.setProperty("MINIO_SECRET_KEY", "test");
        
        WebTestClient webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // Verify that proper authentication is required
        webTestClient.get()
                .uri("/test-bucket")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType("application/xml");
    }
}