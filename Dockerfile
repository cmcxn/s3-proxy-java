# Dockerfile for S3 Proxy Java
FROM eclipse-temurin:17-jre

# 创建应用用户
RUN groupadd -r s3proxy && useradd -r -g s3proxy s3proxy

# 设置工作目录
WORKDIR /app

# 创建数据目录并设置权限
RUN mkdir -p /app/data && chown s3proxy:s3proxy /app/data

# 复制预构建的 JAR 文件
COPY target/s3-proxy-java-0.1.0.jar app.jar

# 更改文件所有者
RUN chown s3proxy:s3proxy app.jar

# 切换到非 root 用户
USER s3proxy

# 暴露端口
EXPOSE 8080

# 环境变量默认值
ENV JAVA_OPTS="-Xms512m -Xmx1024m" \
    SPRING_PROFILES_ACTIVE="h2" \
    MINIO_ENDPOINT="http://localhost:9000" \
    MINIO_ACCESS_KEY="minioadmin" \
    MINIO_SECRET_KEY="minioadmin" \
    MINIO_DEDUPE_BUCKET="dedupe-storage" \
    S3_AUTH_ENABLED="true"

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]