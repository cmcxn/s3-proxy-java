# Java S3 兼容代理服务 (Spring Boot WebFlux)

基于 Spring Boot WebFlux 构建的 S3 兼容 API 服务，提供对 MinIO/S3 存储的直接 S3 兼容端点访问，具备文件去重功能。

## 项目介绍

本项目是一个高性能的 S3 兼容代理服务，采用响应式编程模型，支持文件去重、多种数据库后端，并提供完整的 S3 API 兼容性。主要用于简化对象存储访问、减少存储空间占用（通过去重功能）、以及提供统一的 S3 接口。

### 主要特性
- ✅ **S3 兼容 API**: 提供标准 S3 API 端点，无需代理路径前缀
- ✅ **文件去重**: 基于内容寻址的存储系统，通过引用计数消除重复文件
- ✅ **文件操作**: 支持上传、下载、删除文件，保留 content-type 头部信息
- ✅ **存储桶操作**: 通过 HEAD 请求检查存储桶是否存在
- ✅ **二进制支持**: 处理所有文件类型，包括二进制数据
- ✅ **响应式架构**: 基于 Spring WebFlux 的非阻塞操作
- ✅ **MinIO SDK 兼容**: 与 MinIO SDK 和其他 S3 客户端兼容
- ✅ **多数据库支持**: 支持 H2（开发/测试）和 MySQL（生产环境）
- ✅ **容器化支持**: 提供 Docker 和 Docker Compose 部署方案

## 核心类介绍

### 主要应用类
- **`S3ProxyApplication`**: Spring Boot 主应用入口类，配置 JPA 仓库扫描
- **`S3CompatibleController`**: 核心控制器，处理所有 S3 兼容的 REST API 请求
- **`MinioConfig`**: MinIO 客户端配置类，管理与 MinIO 服务器的连接
- **`S3AuthenticationFilter`**: S3 认证过滤器，处理 AWS 签名验证

### 服务层类
- **`DeduplicationService`**: 文件去重服务，管理文件存储和引用计数
- **`HashService`**: 哈希计算服务，用于文件内容去重的哈希值计算
- **`MultipartUploadService`**: 多部分上传服务，处理大文件的分片上传

### 数据层类
- **`FileEntity`**: 文件实体类，存储文件元数据和哈希信息
- **`UserFileEntity`**: 用户文件关联实体类，管理用户与文件的映射关系
- **`DatabaseConfig`**: 数据库配置类，根据不同 profile 提供 H2 或 MySQL 配置

## Java 直接启动方式

### 方式一：使用 Maven 启动

#### 1. 使用 H2 数据库启动（默认配置）
```bash
# 设置 MinIO 连接环境变量
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin

# 启动应用（使用内嵌 H2 数据库）
mvn spring-boot:run
```

#### 2. 使用 MySQL 数据库启动
```bash
# 设置 MinIO 环境变量
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin

# 设置 MySQL 数据库配置
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_HOST=localhost
export MYSQL_PORT=3306
export MYSQL_DB=s3proxy
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=yourpassword

# 启动应用
mvn spring-boot:run
```

### 方式二：使用 JAR 包启动

#### 1. 构建 JAR 包
```bash
# 构建包含所有依赖的 fat JAR（包含 H2 和 MySQL 驱动）
mvn clean package -DskipTests

# 验证构建结果
ls -la target/s3-proxy-java-0.1.0.jar
```

#### 2. 使用 H2 数据库运行
```bash
# 设置环境变量
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin

# 启动应用（默认使用 H2）
java -jar target/s3-proxy-java-0.1.0.jar

# 或者明确指定 H2 profile
SPRING_PROFILES_ACTIVE=h2 java -jar target/s3-proxy-java-0.1.0.jar
```

#### 3. 使用 MySQL 数据库运行
```bash
# 方式一：通过环境变量
SPRING_PROFILES_ACTIVE=mysql \
MYSQL_HOST=localhost \
MYSQL_PORT=3306 \
MYSQL_DB=s3proxy \
MYSQL_USERNAME=root \
MYSQL_PASSWORD=yourpassword \
MINIO_ENDPOINT=http://localhost:9000 \
MINIO_ACCESS_KEY=minioadmin \
MINIO_SECRET_KEY=minioadmin \
java -jar target/s3-proxy-java-0.1.0.jar

# 方式二：通过 JVM 参数
java -jar target/s3-proxy-java-0.1.0.jar \
  --spring.profiles.active=mysql \
  --spring.datasource.url=jdbc:mysql://localhost:3306/s3proxy \
  --spring.datasource.username=root \
  --spring.datasource.password=yourpassword
```

### 快速演示脚本
```bash
# 使用 H2 数据库的快速演示
./demo.sh

# 使用 MySQL 数据库的快速演示
./mysql-demo.sh
```

## Docker 启动方式

### 方式一：使用 Docker 直接构建和运行

#### 1. 构建 Docker 镜像
```bash
# 构建镜像
docker build -t s3-proxy-java:latest .

# 查看镜像信息
docker images s3-proxy-java
```

#### 2. 运行容器（H2 数据库）
```bash
# 基本运行（使用默认 H2 数据库）
docker run -d \
  --name s3-proxy \
  -p 8080:8080 \
  -e MINIO_ENDPOINT=http://host.docker.internal:9000 \
  -e MINIO_ACCESS_KEY=minioadmin \
  -e MINIO_SECRET_KEY=minioadmin \
  s3-proxy-java:latest

# 查看日志
docker logs -f s3-proxy
```

#### 3. 运行容器（MySQL 数据库）
```bash
# 使用 MySQL 数据库运行
docker run -d \
  --name s3-proxy-mysql \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=mysql \
  -e MYSQL_HOST=host.docker.internal \
  -e MYSQL_PORT=3306 \
  -e MYSQL_DB=s3proxy \
  -e MYSQL_USERNAME=root \
  -e MYSQL_PASSWORD=yourpassword \
  -e MINIO_ENDPOINT=http://host.docker.internal:9000 \
  -e MINIO_ACCESS_KEY=minioadmin \
  -e MINIO_SECRET_KEY=minioadmin \
  s3-proxy-java:latest
```

### 方式二：使用 Docker Compose（推荐）

项目提供了完整的 Docker Compose 配置，包含 S3 Proxy 应用、MinIO 服务和 MySQL 数据库。

#### 1. 启动完整环境（H2 数据库 + MinIO）
```bash
# 启动 S3 Proxy 和 MinIO 服务
docker-compose up -d

# 查看服务状态
docker-compose ps

# 查看日志
docker-compose logs -f s3-proxy
```

#### 2. 启动完整环境（MySQL + MinIO）
```bash
# 启动包含 MySQL 的完整环境
docker-compose --profile mysql up -d

# 查看所有服务状态
docker-compose --profile mysql ps

# 查看特定服务日志
docker-compose logs -f s3-proxy-mysql
```

#### 3. 服务访问地址
启动后，可以通过以下地址访问各个服务：

- **S3 Proxy API**: http://localhost:8080
- **S3 Proxy API (MySQL)**: http://localhost:8081 (仅在使用 MySQL profile 时)
- **MinIO 控制台**: http://localhost:9001 (账号: minioadmin/minioadmin)
- **MinIO API**: http://localhost:9000
- **MySQL**: localhost:3306 (仅在使用 MySQL profile 时)

#### 4. 停止和清理
```bash
# 停止服务
docker-compose down

# 停止服务并删除数据卷
docker-compose down -v

# 停止 MySQL 环境
docker-compose --profile mysql down
```

### Docker 环境变量配置

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `h2` | 数据库配置文件 (h2/mysql) |
| `MINIO_ENDPOINT` | `http://localhost:9000` | MinIO 服务端点 |
| `MINIO_ACCESS_KEY` | `minioadmin` | MinIO 访问密钥 |
| `MINIO_SECRET_KEY` | `minioadmin` | MinIO 秘密密钥 |
| `MINIO_DEDUPE_BUCKET` | `dedupe-storage` | 去重存储桶名称 |
| `S3_AUTH_ENABLED` | `true` | 是否启用 S3 认证 |
| `MYSQL_HOST` | `localhost` | MySQL 主机地址 |
| `MYSQL_PORT` | `3306` | MySQL 端口 |
| `MYSQL_DB` | `s3proxy` | MySQL 数据库名 |
| `MYSQL_USERNAME` | `root` | MySQL 用户名 |
| `MYSQL_PASSWORD` | (空) | MySQL 密码 |
| `JAVA_OPTS` | `-Xms512m -Xmx1024m` | JVM 参数 |

## API 端点和使用示例

### S3 兼容端点

服务提供标准的 S3 兼容端点：

| 方法 | 端点 | 描述 |
|-----|------|------|
| PUT | `/{bucket}/{key}` | 上传文件到指定存储桶和键 |
| GET | `/{bucket}/{key}` | 从存储桶下载文件 |
| DELETE | `/{bucket}/{key}` | 从存储桶删除文件 |
| HEAD | `/{bucket}` | 检查存储桶是否存在 |
| HEAD | `/{bucket}/{key}` | 获取对象元数据 |
| GET | `/{bucket}?list-type=2` | 列出存储桶中的对象 |

### 基本使用示例

#### 1. 文件上传
```bash
# 上传文件
curl -X PUT \
  --data-binary @example.txt \
  "http://localhost:8080/mybucket/folder/example.txt"

# 上传二进制文件
curl -X PUT \
  --data-binary @image.jpg \
  -H "Content-Type: image/jpeg" \
  "http://localhost:8080/mybucket/images/image.jpg"
```

#### 2. 文件下载
```bash
# 下载文件
curl -L "http://localhost:8080/mybucket/folder/example.txt" -o downloaded.txt

# 获取文件元数据
curl -I "http://localhost:8080/mybucket/folder/example.txt"
```

#### 3. 文件删除
```bash
# 删除文件
curl -X DELETE "http://localhost:8080/mybucket/folder/example.txt"
```

#### 4. 存储桶操作
```bash
# 检查存储桶是否存在
curl -I "http://localhost:8080/mybucket"

# 列出存储桶对象
curl "http://localhost:8080/mybucket?list-type=2"

# 列出指定前缀的对象
curl "http://localhost:8080/mybucket?list-type=2&prefix=folder/"
```

### MinIO SDK 集成示例

```java
// Java MinIO SDK 示例
MinioClient client = MinioClient.builder()
    .endpoint("http://localhost:8080")  // 代理端点
    .credentials("minioadmin", "minioadmin")
    .build();

// 上传文件
client.putObject(
    PutObjectArgs.builder()
        .bucket("mybucket")
        .object("myfile.txt")
        .stream(inputStream, inputStream.available(), -1)
        .build()
);

// 下载文件
GetObjectResponse response = client.getObject(
    GetObjectArgs.builder()
        .bucket("mybucket")
        .object("myfile.txt")
        .build()
);

// 列出对象
Iterable<Result<Item>> results = client.listObjects(
    ListObjectsArgs.builder()
        .bucket("mybucket")
        .prefix("folder/")
        .recursive(true)
        .build()
);
```

## 数据库配置

服务支持两种数据库后端：

### H2 数据库（默认）
- **用途**: 适用于开发和测试环境
- **特点**: 内嵌数据库，无需额外安装
- **数据存储**: `./data/s3proxy.mv.db`
- **管理界面**: http://localhost:8080/h2-console

```bash
# 使用 H2 数据库（默认配置）
mvn spring-boot:run

# 或明确指定
export SPRING_PROFILES_ACTIVE=h2
mvn spring-boot:run
```

### MySQL 数据库（生产环境推荐）
- **用途**: 适用于生产环境
- **特点**: 高性能、并发访问、优化索引
- **连接池**: 支持 HikariCP 连接池优化

#### MySQL 设置步骤

1. **创建数据库**
```sql
CREATE DATABASE s3proxy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 可选：创建专用用户
CREATE USER 's3proxy'@'%' IDENTIFIED BY 'your_secure_password';
GRANT ALL PRIVILEGES ON s3proxy.* TO 's3proxy'@'%';
FLUSH PRIVILEGES;
```

2. **配置环境变量**
```bash
export SPRING_PROFILES_ACTIVE=mysql
export MYSQL_HOST=localhost
export MYSQL_PORT=3306
export MYSQL_DB=s3proxy
export MYSQL_USERNAME=s3proxy
export MYSQL_PASSWORD=your_secure_password
```

3. **启动应用**
```bash
mvn spring-boot:run
```

应用会自动：
- 使用 Flyway 创建所需的表和优化索引
- 建立适当的外键关系
- 配置连接池以获得最佳性能

详细的 MySQL 配置信息请参考 [MYSQL_CONFIGURATION.md](MYSQL_CONFIGURATION.md)。

## 文件去重功能

本服务包含先进的文件去重功能，可自动消除重复文件：

- **内容寻址存储**: 基于文件内容的 SHA-256 哈希值进行存储
- **引用计数**: 多个用户可以引用同一个物理文件
- **自动清理**: 当引用计数为零时自动删除物理文件
- **透明操作**: 对用户完全透明，不影响正常的 S3 API 使用

详细的去重系统工作原理请参考 [DEDUPLICATION.md](DEDUPLICATION.md)。

## 测试和验证

### 运行测试
```bash
# 运行所有测试
mvn test

# 跳过测试构建
mvn package -DskipTests
```

### 测试类型
1. **MinIO SDK 兼容性测试** (`MinioSdkCompatibilityDemoTest`): 测试 MinIO SDK 集成
2. **MinIO SDK 单元测试** (`MinioSdkDirectProxyUnitTest`): MinIO SDK 操作的单元测试
3. **对象列表测试** (`ObjectListingTest`): 对象列表功能测试
4. **去重功能测试** (`DeduplicationIntegrationTest`): 文件去重功能测试

### 测试覆盖范围
- ✅ MinIO SDK 兼容性
- ✅ S3 兼容 API 端点
- ✅ 错误处理和边界情况
- ✅ 通过 Testcontainers 的真实 MinIO 集成

手动测试说明请参考 [MANUAL_TESTING.md](MANUAL_TESTING.md)。

## 生产环境注意事项

### 性能优化
- 对于大对象优先使用零拷贝流；可替换为 `DataBufferUtils.write(...)` + `PipedInputStream`
- 如果在 Nginx/Ingress 后面运行，请保留 `Host` 头部以支持预签名 URL 流程
- 在公开暴露前添加认证（如 JWT）和速率限制

### 安全配置
- 生产环境建议禁用 H2 控制台
- 使用强密码和密钥轮换
- 配置 HTTPS 和 SSL 数据库连接
- 定期备份数据库

### 监控和运维
- 监控数据库连接池状态
- 设置适当的 JVM 堆内存配置
- 配置日志轮换和存储
- 监控磁盘空间使用情况

## 故障排除

### 常见问题

1. **连接 MinIO 失败**
   - 检查 `MINIO_ENDPOINT` 环境变量是否正确
   - 确认 MinIO 服务是否运行
   - 验证访问密钥和秘密密钥

2. **数据库连接问题**
   - MySQL: 检查连接字符串和凭据
   - H2: 确认数据目录的写入权限

3. **Docker 网络问题**
   - 在 Docker 环境中使用 `host.docker.internal` 访问宿主机服务
   - 检查容器之间的网络连接

4. **文件上传失败**
   - 检查 MinIO 存储桶是否存在并可访问
   - 验证文件大小限制
   - 查看应用日志了解具体错误信息

## 项目结构

```
src/main/java/com/example/s3proxy/
├── S3ProxyApplication.java          # 主应用入口
├── S3CompatibleController.java      # S3 API 控制器
├── MinioConfig.java                 # MinIO 客户端配置
├── S3AuthenticationFilter.java     # S3 认证过滤器
├── config/
│   └── DatabaseConfig.java          # 数据库配置
├── entity/
│   ├── FileEntity.java              # 文件实体
│   └── UserFileEntity.java          # 用户文件关联实体
├── repository/
│   ├── FileRepository.java          # 文件仓库接口
│   └── UserFileRepository.java      # 用户文件仓库接口
├── service/
│   ├── DeduplicationService.java    # 去重服务
│   ├── HashService.java             # 哈希计算服务
│   └── MultipartUploadService.java  # 多部分上传服务
└── util/                            # 工具类
```

## 许可证

本项目采用 MIT 许可证 - 详情请参阅 [LICENSE](LICENSE) 文件。

## 贡献

欢迎提交 Issue 和 Pull Request 来改进这个项目。

## 相关文档

- [MySQL 配置详细说明](MYSQL_CONFIGURATION.md)
- [文件去重系统说明](DEDUPLICATION.md)
- [手动测试指南](MANUAL_TESTING.md)
- [MinIO SDK 兼容性说明](MINIO_SDK_COMPATIBILITY.md)
- [对象列表功能演示](OBJECT_LISTING_DEMO.md)

