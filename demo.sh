#!/bin/bash

# S3 Proxy Java Demo Script
echo "🚀 S3 Proxy Java Demo"
echo "===================="

# Build the project
echo "📦 Building the project..."
mvn clean package -DskipTests

# Check if build was successful
if [ $? -eq 0 ]; then
    echo "✅ Build successful!"
    echo ""
    
    echo "🏃‍♂️ Starting S3 Proxy Service..."
    echo "Service will be available at: http://localhost:8080"
    echo "Proxy endpoints:"
    echo "  PUT    /proxy/{bucket}/{key}"
    echo "  GET    /proxy/{bucket}/{key}"
    echo "  DELETE /proxy/{bucket}/{key}"
    echo "  GET    /proxy/presign/{bucket}/{key}?method={GET|PUT|DELETE}&expiry={seconds}"
    echo ""
    
    echo "💡 Before using the service, make sure you have:"
    echo "  1. Minio running at http://localhost:9000"
    echo "  2. Environment variables set (or use defaults):"
    echo "     - MINIO_ENDPOINT=http://localhost:9000"
    echo "     - MINIO_ACCESS_KEY=minioadmin"
    echo "     - MINIO_SECRET_KEY=minioadmin"
    echo ""
    
    echo "📚 See MANUAL_TESTING.md for detailed testing instructions"
    echo ""
    echo "Press Ctrl+C to stop the service"
    echo "Starting in 3 seconds..."
    sleep 3
    
    # Run the application
    java -jar target/s3-proxy-java-0.1.0.jar
else
    echo "❌ Build failed! Please check the errors above."
    exit 1
fi