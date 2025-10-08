package com.example.s3proxy;

import com.example.s3proxy.service.DeduplicationService;
import com.example.s3proxy.service.MultipartUploadService;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Collections;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BucketTrailingSlashCompatibilityTest {

    @LocalServerPort
    private int port;

    @MockBean
    private DeduplicationService deduplicationService;

    @MockBean
    private MultipartUploadService multipartUploadService;

    @MockBean
    private MinioClient minioClient;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() throws Exception {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();

        Mockito.doReturn(Collections.emptyList())
                .when(deduplicationService)
                .listObjects(Mockito.anyString(), Mockito.anyString());

        Mockito.doReturn(true)
                .when(minioClient)
                .bucketExists(Mockito.any());
    }

    @Test
    void headRequestWithTrailingSlashDoesNotEchoAuthorizationHeader() {
        webTestClient.head()
                .uri("/sample-bucket/")
                .header(HttpHeaders.AUTHORIZATION, awsAuthHeader())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist(HttpHeaders.AUTHORIZATION);
    }

    @Test
    void getRequestWithTrailingSlashReturnsListingWithoutAuthorizationHeader() {
        webTestClient.get()
                .uri("/sample-bucket/")
                .header(HttpHeaders.AUTHORIZATION, awsAuthHeader())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/xml")
                .expectHeader().doesNotExist(HttpHeaders.AUTHORIZATION)
                .expectBody()
                .consumeWith(result -> {
                    byte[] responseBody = result.getResponseBody();
                    if (responseBody != null && responseBody.length > 0) {
                        String body = new String(responseBody);
                        org.assertj.core.api.Assertions.assertThat(body)
                                .contains("<ListBucketResult")
                                .contains("<Name>sample-bucket</Name>");
                    }
                });
    }

    private String awsAuthHeader() {
        return "AWS4-HMAC-SHA256 Credential=minioadmin/20250101/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-date, Signature=test";
    }
}
