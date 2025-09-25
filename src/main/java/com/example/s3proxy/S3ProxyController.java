package com.example.s3proxy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;


import java.io.InputStream;
import java.time.Duration;
import java.util.Map;


@RestController
@RequestMapping("/proxy")
public class S3ProxyController {
private static final Logger log = LoggerFactory.getLogger(S3ProxyController.class);
private final MinioClient minio;


public S3ProxyController(MinioClient minio) { this.minio = minio; }


// GET /proxy/{bucket}/{**key}
@GetMapping(value = "/{bucket}/{**key}")
public Mono<ResponseEntity<byte[]>> get(
@PathVariable String bucket,
@PathVariable("key") String key) {
return Mono.fromCallable(() -> {
try (GetObjectResponse obj = minio.getObject(GetObjectArgs.builder()
.bucket(bucket).`object`(key).build())) {
byte[] data = obj.readAllBytes();
HttpHeaders h = new HttpHeaders();
if (obj.headers().get("Content-Type") != null) {
h.setContentType(MediaType.parseMediaType(obj.headers().get("Content-Type")));
}
return new ResponseEntity<>(data, h, HttpStatus.OK);
}
});
}


// PUT /proxy/{bucket}/{**key}
@PutMapping(value = "/{bucket}/{**key}")
public Mono<ResponseEntity<Void>> put(
@PathVariable String bucket,
@PathVariable("key") String key,
ServerWebExchange exchange) {
return exchange.getRequest().getBody()
.reduce(new java.io.ByteArrayOutputStream(), (baos, dataBuffer) -> {
try {
dataBuffer.readableByteBuffers().forEach(bb -> {
byte[] bytes = new byte[bb.remaining()];
bb.get(bytes);
try { baos.write(bytes); } catch (Exception ignored) {}
});
} finally {
dataBuffer.release();
}
return baos;
})
.flatMap(baos -> Mono.fromCallable(() -> {
byte[] bytes = baos.toByteArray();
try (InputStream is = new java.io.ByteArrayInputStream(bytes)) {
String contentType = exchange.getRequest().getHeaders().getFirst("Content-Type");
PutObjectArgs.Builder b = PutObjectArgs.builder()
.bucket(bucket).`object`(key)
.stream(is, bytes.length, -1);
if (contentType != null) b.contentType(contentType);
minio.putObject(b.build());
}
return ResponseEntity.status(HttpStatus.CREATED).<Void>build();
}));
}


// DELETE /proxy/{bucket}/{**key}
@DeleteMapping(value = "/{bucket}/{**key}")
public Mono<ResponseEntity<Void>> delete(
@PathVariable String bucket,
@PathVariable("key") String key) {
return Mono.fromCallable(() -> {
minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(key).build());
return ResponseEntity.noContent().build();
});
}


// GET /proxy/presign/{bucket}/{**key}?method=GET&expiry=600
@GetMapping("/presign/{bucket}/{**key}")
public Mono<Map<String, String>> presign(
@PathVariable String bucket,
@PathVariable("key") String key,
@RequestParam(defaultValue = "GET") String method,
@RequestParam(defaultValue = "600") Integer expirySeconds) {
return Mono.fromCallable(() -> {
Method m = switch (method.toUpperCase()) {
case "PUT" -> Method.PUT; case "POST" -> Method.POST; case "DELETE" -> Method.DELETE; default -> Method.GET; };
String url = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
.method(m).bucket(bucket).`object`(key).expiry(expirySeconds).build());
return Map.of("url", url, "method", method);
});
}
}