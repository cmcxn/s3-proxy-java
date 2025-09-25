package com.example.s3proxy;


import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class MinioConfig {
@Bean
MinioClient minioClient(
@Value("${MINIO_ENDPOINT:http://127.0.0.1:9000}") String endpoint,
@Value("${MINIO_ACCESS_KEY:minioadmin}") String accessKey,
@Value("${MINIO_SECRET_KEY:minioadmin}") String secretKey) {
return MinioClient.builder()
.endpoint(endpoint)
.credentials(accessKey, secretKey)
.build();
}
}