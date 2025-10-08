#!/bin/bash

# S3 Proxy Docker 测试脚本

echo "🐳 S3 Proxy Docker 测试脚本"
echo "=========================="

# 检查是否已构建 JAR 文件
if [ ! -f "target/s3-proxy-java-0.1.0.jar" ]; then
    echo "📦 构建 JAR 文件..."
    mvn clean package -DskipTests -B
    if [ $? -ne 0 ]; then
        echo "❌ JAR 构建失败"
        exit 1
    fi
    echo "✅ JAR 构建成功"
fi

# 构建 Docker 镜像
echo "🔨 构建 Docker 镜像..."
docker build -t s3-proxy-java:local .
if [ $? -ne 0 ]; then
    echo "❌ Docker 镜像构建失败"
    exit 1
fi
echo "✅ Docker 镜像构建成功"

# 停止并删除现有容器（如果存在）
echo "🧹 清理现有容器..."
docker stop s3-proxy-local 2>/dev/null || true
docker rm s3-proxy-local 2>/dev/null || true

# 启动容器
echo "🚀 启动 S3 Proxy 容器..."
docker run -d \
  --name s3-proxy-local \
  -p 8080:8080 \
  -e MINIO_ENDPOINT=http://host.docker.internal:9000 \
  -e MINIO_ACCESS_KEY=minioadmin \
  -e MINIO_SECRET_KEY=minioadmin \
  s3-proxy-java:local

if [ $? -ne 0 ]; then
    echo "❌ 容器启动失败"
    exit 1
fi

echo "✅ 容器启动成功"
echo ""
echo "📋 容器信息:"
echo "   - 容器名称: s3-proxy-local"
echo "   - 端口映射: 8080:8080"
echo "   - 访问地址: http://localhost:8080"
echo ""

# 等待服务启动
echo "⏳ 等待服务启动..."
sleep 10

# 检查服务状态
echo "🔍 检查服务状态..."
if curl -s -I http://localhost:8080/ > /dev/null; then
    echo "✅ 服务运行正常"
    echo ""
    echo "📊 容器日志 (最后 10 行):"
    docker logs s3-proxy-local | tail -10
    echo ""
    echo "🎯 测试命令:"
    echo "   curl -I http://localhost:8080/"
    echo ""
    echo "🛑 停止容器命令:"
    echo "   docker stop s3-proxy-local"
    echo "   docker rm s3-proxy-local"
else
    echo "❌ 服务可能未正常启动，查看日志:"
    docker logs s3-proxy-local
    echo ""
    echo "🛑 清理容器..."
    docker stop s3-proxy-local
    docker rm s3-proxy-local
    exit 1
fi