package com.example.s3proxy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Test to verify that S3 authentication is enabled by default and works correctly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "s3.auth.enabled=true",
    "MINIO_ENDPOINT=http://localhost:9999",
    "MINIO_ACCESS_KEY=minioadmin",
    "MINIO_SECRET_KEY=minioadmin"
})
public class AuthenticationEnabledTest {
    
    @LocalServerPort
    private int port;

    @Test
    void testS3RequestsRequireAuthenticationWhenEnabled() throws Exception {
        WebTestClient webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // Test that the endpoint requires authentication when enabled (default)
        webTestClient.get()
                .uri("/test-bucket")
                .exchange()
                .expectStatus().isForbidden() // Should fail without authentication
                .expectHeader().contentType("application/xml")
                .expectBody(String.class)
                .value(body -> {
                    assert body.contains("<Error>");
                    assert body.contains("<Code>AccessDenied</Code>");
                    assert body.contains("<Message>Missing Authorization header</Message>");
                });
    }

    @Test
    void testS3RequestsWithValidAuthenticationPass() throws Exception {
        WebTestClient webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // Test that the endpoint accepts valid authentication when enabled
        webTestClient.get()
                .uri("/test-bucket")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=minioadmin/20241226/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=dummy")
                .header("x-amz-date", "20241226T000000Z")
                .exchange()
                .expectStatus().isOk(); // Should succeed with valid credentials
    }
}