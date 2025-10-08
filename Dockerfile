# Dockerfile for S3 Proxy Java
FROM eclipse-temurin:17-jdk

# 安装 Maven
RUN apt-get update && \
    apt-get install -y maven && \
    rm -rf /var/lib/apt/lists/*

# 设置工作目录
WORKDIR /app

# 复制项目文件
COPY . .

# 构建应用
RUN mvn clean package -DskipTests -B

# 创建应用用户
RUN groupadd -r s3proxy && useradd -r -g s3proxy s3proxy

# 更改文件所有者
RUN chown -R s3proxy:s3proxy /app

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
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar target/s3-proxy-java-0.1.0.jar"]