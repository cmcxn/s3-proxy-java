# Docker 发布流水线说明

## 概述

本项目包含了完整的 Docker 发布流水线，可以自动构建和发布 Docker 镜像到 Docker Hub。

## 工作流程

### 自动触发条件

- **推送到 master 分支**: 自动构建并发布镜像
- **创建版本标签**: 自动构建并发布带版本标签的镜像
- **Pull Request**: 仅构建镜像，不发布

### 镜像标签策略

| 触发条件 | 生成的标签 | 示例 |
|---------|-----------|------|
| 推送到 master | `latest` | `username/s3-proxy-java:latest` |
| 创建标签 v1.0.0 | `v1.0.0`, `1.0`, `1` | `username/s3-proxy-java:v1.0.0` |
| Pull Request | `pr-123` | `username/s3-proxy-java:pr-123` |

### 支持的平台

- `linux/amd64` (x86_64)
- `linux/arm64` (ARM64)

## 配置要求

### 必需的 GitHub Secrets

在 GitHub 仓库设置中添加以下 secrets：

1. **DOCKER_USERNAME**: Docker Hub 用户名
2. **DOCKER_PASSWORD**: Docker Hub 访问令牌

### 配置步骤

1. **登录 [Docker Hub](https://hub.docker.com)**
2. **创建访问令牌**:
   - 前往 Account Settings > Security
   - 点击 "New Access Token"
   - 输入令牌名称 (例如：`github-actions`)
   - 选择权限范围 (建议选择 `Read, Write, Delete`)
   - 点击 "Generate" 并保存生成的令牌

3. **在 GitHub 仓库中设置 Secrets**:
   - 进入仓库 Settings > Secrets and variables > Actions
   - 点击 "New repository secret"
   - 添加 `DOCKER_USERNAME` = 你的 Docker Hub 用户名
   - 添加 `DOCKER_PASSWORD` = 刚才生成的访问令牌 (不是密码！)

## 使用方法

### 发布到 Docker Hub

#### 方法一：推送到 master 分支
```bash
git push origin master
```
这将自动构建并发布 `latest` 标签。

#### 方法二：创建版本标签
```bash
git tag v1.0.0
git push origin v1.0.0
```
这将自动构建并发布版本化的镜像。

### 使用发布的镜像

```bash
# 使用 latest 标签
docker pull your-username/s3-proxy-java:latest
docker run -d -p 8080:8080 your-username/s3-proxy-java:latest

# 使用特定版本
docker pull your-username/s3-proxy-java:v1.0.0
docker run -d -p 8080:8080 your-username/s3-proxy-java:v1.0.0
```

### 更新 docker-compose.yml

更新项目中的 docker-compose.yml 文件：

```yaml
version: '3.8'
services:
  s3-proxy:
    image: your-username/s3-proxy-java:latest
    # 其他配置保持不变...
```

## 工作流程文件

工作流程定义在 `.github/workflows/docker-publish.yml` 中，包含以下主要步骤：

1. **代码检出**: 获取源代码
2. **Docker Buildx 设置**: 启用多平台构建
3. **Docker Hub 登录**: 使用提供的凭据登录
4. **元数据提取**: 生成标签和标签
5. **构建和推送**: 构建镜像并推送到 Docker Hub
6. **构建证明**: 生成构建来源证明

## 故障排除

### 常见问题

1. **Docker Hub 登录失败**
   - 检查 DOCKER_USERNAME 和 DOCKER_PASSWORD 是否正确设置
   - 确认 Docker Hub 访问令牌有推送权限
   - 工作流会自动验证凭据是否存在

2. **权限错误 (ACTIONS_ID_TOKEN_REQUEST_URL)**
   - 确保仓库已启用 GitHub Actions
   - 检查工作流权限设置包含 `id-token: write` 和 `attestations: write`

3. **构建失败**
   - 检查 Dockerfile 语法
   - 确认所有依赖项可访问
   - 确保 Maven 构建步骤成功完成

4. **推送失败**
   - 检查仓库名称是否正确
   - 确认 Docker Hub 仓库存在且有推送权限
   - 验证标签格式是否正确

5. **安全警告**
   - 敏感环境变量不再硬编码在 Dockerfile 中
   - 使用运行时环境变量配置敏感信息

### 调试方法

1. 查看 GitHub Actions 日志
2. 本地测试 Docker 构建：
   ```bash
   docker build -t s3-proxy-java:test .
   ```

## 安全注意事项

### Docker 镜像安全
- ✅ 使用非 root 用户运行应用
- ✅ 敏感环境变量不再硬编码在镜像中
- ✅ 最小权限原则：只暴露必要的端口和目录

### 环境变量配置
敏感信息现在需要在运行时提供：

```bash
# 正确的运行方式
docker run -d \
  --name s3-proxy \
  -p 8080:8080 \
  -e MINIO_ENDPOINT=http://your-minio:9000 \
  -e MINIO_ACCESS_KEY=your-access-key \
  -e MINIO_SECRET_KEY=your-secret-key \
  -e S3_AUTH_ENABLED=true \
  your-username/s3-proxy-java:latest
```

### GitHub Actions 安全
- 使用 repository secrets 存储敏感信息
- 启用了构建证明 (build attestation) 功能
- 支持 OIDC 令牌验证

- 使用 Docker Hub 访问令牌而非密码
- 定期轮换访问令牌
- 镜像使用非 root 用户运行
- 启用 Docker 内容信任 (可选)

## 优化建议

- 使用 `.dockerignore` 减少构建上下文大小
- 定期清理未使用的镜像标签
- 考虑使用镜像扫描工具检查安全漏洞