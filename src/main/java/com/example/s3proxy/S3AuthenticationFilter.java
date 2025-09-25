package com.example.s3proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Simple authentication filter for S3-compatible endpoints.
 * Validates basic authentication or AWS signature against configured credentials.
 */
@Component
@Order(-100) // Run before other filters
public class S3AuthenticationFilter implements WebFilter {
    
    private static final Logger log = LoggerFactory.getLogger(S3AuthenticationFilter.class);
    
    @Value("${MINIO_ACCESS_KEY:minioadmin}")
    private String expectedAccessKey;
    
    @Value("${MINIO_SECRET_KEY:minioadmin}")
    private String expectedSecretKey;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        
        // Only apply authentication to S3-compatible root endpoints, not /proxy endpoints
        if (path.startsWith("/proxy") || path.equals("/actuator") || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }
        
        // For root-level paths that look like S3 API calls, validate authentication
        if (isS3ApiCall(path)) {
            return validateAuthentication(exchange, chain);
        }
        
        return chain.filter(exchange);
    }
    
    private boolean isS3ApiCall(String path) {
        // S3 API calls have patterns like:
        // /{bucket}
        // /{bucket}/{key}
        // /presign/{bucket}/{key}
        if (path.equals("/") || path.equals("/favicon.ico")) {
            return false;
        }
        
        String[] parts = path.split("/");
        return parts.length >= 2; // At least /bucket or /presign/bucket
    }
    
    private Mono<Void> validateAuthentication(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        
        if (authHeader == null) {
            return handleAuthenticationFailure(exchange, "Missing Authorization header");
        }
        
        try {
            // Handle AWS signature authentication (starts with "AWS4-HMAC-SHA256")
            if (authHeader.startsWith("AWS4-HMAC-SHA256")) {
                return validateAwsSignature(exchange, chain, authHeader);
            }
            
            // Handle basic authentication (starts with "Basic ")
            if (authHeader.startsWith("Basic ")) {
                return validateBasicAuth(exchange, chain, authHeader);
            }
            
            log.debug("Unsupported authorization scheme: {}", authHeader.substring(0, Math.min(20, authHeader.length())));
            return handleAuthenticationFailure(exchange, "Unsupported authorization scheme");
            
        } catch (Exception e) {
            log.error("Authentication validation error: ", e);
            return handleAuthenticationFailure(exchange, "Authentication validation failed");
        }
    }
    
    private Mono<Void> validateAwsSignature(ServerWebExchange exchange, WebFilterChain chain, String authHeader) {
        try {
            // Extract access key from AWS signature
            // Format: AWS4-HMAC-SHA256 Credential=ACCESS_KEY/date/region/service/aws4_request, SignedHeaders=..., Signature=...
            String credentialPart = extractCredentialFromAwsAuth(authHeader);
            if (credentialPart != null && credentialPart.startsWith(expectedAccessKey + "/")) {
                log.debug("AWS signature authentication passed for access key: {}", expectedAccessKey);
                return chain.filter(exchange);
            }
            
            log.debug("AWS signature authentication failed - access key mismatch");
            return handleAuthenticationFailure(exchange, "Invalid credentials");
            
        } catch (Exception e) {
            log.error("AWS signature validation error: ", e);
            return handleAuthenticationFailure(exchange, "AWS signature validation failed");
        }
    }
    
    private String extractCredentialFromAwsAuth(String authHeader) {
        // Find "Credential=" in the authorization header
        int credIndex = authHeader.indexOf("Credential=");
        if (credIndex == -1) return null;
        
        int start = credIndex + "Credential=".length();
        int end = authHeader.indexOf(",", start);
        if (end == -1) end = authHeader.length();
        
        return authHeader.substring(start, end);
    }
    
    private Mono<Void> validateBasicAuth(ServerWebExchange exchange, WebFilterChain chain, String authHeader) {
        try {
            String base64Credentials = authHeader.substring("Basic ".length());
            String credentials = new String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8);
            String[] credArray = credentials.split(":", 2);
            
            if (credArray.length == 2) {
                String username = credArray[0];
                String password = credArray[1];
                
                if (expectedAccessKey.equals(username) && expectedSecretKey.equals(password)) {
                    log.debug("Basic authentication passed for user: {}", username);
                    return chain.filter(exchange);
                }
            }
            
            log.debug("Basic authentication failed");
            return handleAuthenticationFailure(exchange, "Invalid credentials");
            
        } catch (Exception e) {
            log.error("Basic authentication validation error: ", e);
            return handleAuthenticationFailure(exchange, "Basic authentication validation failed");
        }
    }
    
    private Mono<Void> handleAuthenticationFailure(ServerWebExchange exchange, String reason) {
        log.debug("Authentication failed: {}", reason);
        
        // Return S3-compatible error response
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().add("Content-Type", "application/xml");
        
        String errorXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Error>
                <Code>AccessDenied</Code>
                <Message>%s</Message>
                <RequestId>%s</RequestId>
            </Error>
            """.formatted(reason, java.util.UUID.randomUUID().toString());
            
        byte[] bytes = errorXml.getBytes(StandardCharsets.UTF_8);
        org.springframework.core.io.buffer.DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}