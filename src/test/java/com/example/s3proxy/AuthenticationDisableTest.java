package com.example.s3proxy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Test to verify that S3 authentication can be disabled via configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "s3.auth.enabled=false",
    "MINIO_ENDPOINT=http://localhost:9999",
    "MINIO_ACCESS_KEY=test",
    "MINIO_SECRET_KEY=test"
})
public class AuthenticationDisableTest {
    
    @LocalServerPort
    private int port;

    @Test
    void testS3RequestsWithoutAuthenticationWhenDisabled() throws Exception {
        WebTestClient webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // Test that the endpoint does not require authentication when disabled
        // This should not return a 403 Forbidden response - should get through to the controller
        webTestClient.get()
                .uri("/test-bucket")
                .exchange()
                .expectStatus().isOk() // Should succeed without authentication
                .expectHeader().contentType("application/xml");
    }

    @Test
    void testS3RequestsWithoutHeadersWhenDisabled() throws Exception {
        WebTestClient webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        // Test that requests without any auth headers work when authentication is disabled
        webTestClient.get()
                .uri("/another-bucket")
                .exchange()
                .expectStatus().isOk() // Should succeed without authentication
                .expectHeader().contentType("application/xml");
    }
}