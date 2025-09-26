package com.example.s3proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories
public class S3ProxyApplication {
public static void main(String[] args) {
SpringApplication.run(S3ProxyApplication.class, args);
}
}