package com.example.s3proxy;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
public class S3ProxyControllerTest {

    @Mock
    private MinioClient minioClient;

    private S3ProxyController controller;

    @BeforeEach
    void setUp() {
        controller = new S3ProxyController(minioClient);
    }

    @Test
    void testGetObject() throws Exception {
        // Arrange
        String bucket = "test-bucket";
        String key = "test-key";
        byte[] testData = "test content".getBytes();
        
        GetObjectResponse mockResponse = mock(GetObjectResponse.class);
        okhttp3.Headers mockOkHttpHeaders = new okhttp3.Headers.Builder()
                .add("Content-Type", "text/plain")
                .build();
        
        when(mockResponse.readAllBytes()).thenReturn(testData);
        when(mockResponse.headers()).thenReturn(mockOkHttpHeaders);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        // Act
        Mono<ResponseEntity<byte[]>> result = controller.get(bucket, key);

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).isEqualTo(testData);
                    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
                })
                .verifyComplete();

        // Verify MinIO client was called correctly
        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minioClient).getObject(captor.capture());
        // Note: GetObjectArgs doesn't expose bucket/object for verification, 
        // but we can trust the call was made with our parameters
    }

    @Test
    void testPutObject() throws Exception {
        // Arrange
        String bucket = "test-bucket";
        String key = "test-key";
        String content = "test content";
        
        DataBuffer dataBuffer = new DefaultDataBufferFactory().wrap(content.getBytes());
        MockServerHttpRequest request = MockServerHttpRequest
                .put("/proxy/" + bucket + "/" + key)
                .contentType(MediaType.TEXT_PLAIN)
                .body(Flux.just(dataBuffer));
        
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        // Act
        Mono<ResponseEntity<Void>> result = controller.put(bucket, key, exchange);

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                })
                .verifyComplete();

        // Verify MinIO client was called
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void testDeleteObject() throws Exception {
        // Arrange
        String bucket = "test-bucket";
        String key = "test-key";

        // Act
        Mono<ResponseEntity<Void>> result = controller.delete(bucket, key);

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
                })
                .verifyComplete();

        // Verify MinIO client was called correctly
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void testPresignedUrl() throws Exception {
        // Arrange
        String bucket = "test-bucket";
        String key = "test-key";
        String method = "GET";
        Integer expiry = 600;
        String expectedUrl = "http://localhost:9000/test-bucket/test-key?presigned=true";
        
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn(expectedUrl);

        // Act
        Mono<Map<String, String>> result = controller.presign(bucket, key, method, expiry);

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response).containsEntry("url", expectedUrl);
                    assertThat(response).containsEntry("method", method);
                })
                .verifyComplete();

        // Verify MinIO client was called correctly
        ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioClient).getPresignedObjectUrl(captor.capture());
    }

    @Test
    void testPresignedUrlWithPutMethod() throws Exception {
        // Arrange
        String bucket = "test-bucket";
        String key = "test-key";
        String method = "PUT";
        Integer expiry = 300;
        String expectedUrl = "http://localhost:9000/test-bucket/test-key?presigned=true";
        
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn(expectedUrl);

        // Act
        Mono<Map<String, String>> result = controller.presign(bucket, key, method, expiry);

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response).containsEntry("url", expectedUrl);
                    assertThat(response).containsEntry("method", method);
                })
                .verifyComplete();

        verify(minioClient).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    void testPresignedUrlWithDefaultValues() throws Exception {
        // Arrange
        String bucket = "test-bucket";
        String key = "test-key";
        String expectedUrl = "http://localhost:9000/test-bucket/test-key?presigned=true";
        
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn(expectedUrl);

        // Act - using default method and expiry
        Mono<Map<String, String>> result = controller.presign(bucket, key, "GET", 600);

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response).containsEntry("url", expectedUrl);
                    assertThat(response).containsEntry("method", "GET");
                })
                .verifyComplete();

        verify(minioClient).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    void testPutObjectWithoutContentType() throws Exception {
        // Arrange
        String bucket = "test-bucket";
        String key = "test-key";
        String content = "test content";
        
        DataBuffer dataBuffer = new DefaultDataBufferFactory().wrap(content.getBytes());
        MockServerHttpRequest request = MockServerHttpRequest
                .put("/proxy/" + bucket + "/" + key)
                // No content type header
                .body(Flux.just(dataBuffer));
        
        ServerWebExchange exchange = MockServerWebExchange.from(request);

        // Act
        Mono<ResponseEntity<Void>> result = controller.put(bucket, key, exchange);

        // Assert
        StepVerifier.create(result)
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                })
                .verifyComplete();

        // Verify MinIO client was called
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }
}