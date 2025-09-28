package com.example.s3proxy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Simple unit test to verify the object listing endpoint is accessible 
 * and returns proper S3-compatible XML structure.
 * This test doesn't require Docker and focuses on the response format.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ObjectListingUnitTest {
    
    @LocalServerPort
    private int port;

    @Test
    void testListObjectsEndpointAccessible() throws Exception {
        // Set up environment variables for a mock MinIO endpoint that won't be reached
        System.setProperty("MINIO_ENDPOINT", "http://localhost:9999");
        System.setProperty("MINIO_ACCESS_KEY", "test");
        System.setProperty("MINIO_SECRET_KEY", "test");
        
        WebTestClient webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // Test that the endpoint responds with proper authentication challenge
        webTestClient.get()
                .uri("/test-bucket")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType("application/xml")
                .expectBody(String.class)
                .value(body -> {
                    assert body.contains("<Error>");
                    assert body.contains("<Code>AccessDenied</Code>");
                    assert body.contains("<Message>Missing Authorization header</Message>");
                });
    }

    @Test
    void testListObjectsWithParameters() throws Exception {
        System.setProperty("MINIO_ENDPOINT", "http://localhost:9999");
        System.setProperty("MINIO_ACCESS_KEY", "test");
        System.setProperty("MINIO_SECRET_KEY", "test");
        
        WebTestClient webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // Test that the endpoint accepts and processes query parameters
        // and still requires authentication 
        webTestClient.get()
                .uri("/test-bucket?prefix=myfolder/&max-keys=10")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType("application/xml")
                .expectBody(String.class)
                .value(body -> {
                    assert body.contains("<Error>");
                    assert body.contains("<Code>AccessDenied</Code>");
                });
    }

    @Test
    void testListObjectsWithAuthenticationHeaders() throws Exception {
        System.setProperty("MINIO_ENDPOINT", "http://localhost:9999");
        System.setProperty("MINIO_ACCESS_KEY", "minioadmin");
        System.setProperty("MINIO_SECRET_KEY", "minioadmin");
        
        WebTestClient webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // Test that authentication fails when MinIO server is not accessible
        // The new implementation validates credentials against MinIO directly,
        // so when MinIO is unavailable, authentication should fail with 403
        webTestClient.get()
                .uri("/test-bucket")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=minioadmin/20241226/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=dummy")
                .header("x-amz-date", "20241226T000000Z")
                .exchange()
                .expectStatus().isForbidden() // Authentication fails when MinIO is unavailable
                .expectHeader().contentType("application/xml")
                .expectBody(String.class)
                .value(body -> {
                    assert body.contains("<Error>");
                    assert body.contains("<Code>AccessDenied</Code>");
                    assert body.contains("<Message>Service unavailable</Message>");
                });
    }
}