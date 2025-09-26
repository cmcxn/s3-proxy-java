#!/bin/bash

# S3-Compatible API Java with MySQL Demo Script
echo "🚀 S3-Compatible API Java with MySQL Demo"
echo "==========================================="

echo "📋 This demo shows how to run S3-proxy-java with MySQL database"
echo ""

# Check if MySQL is available
if command -v mysql >/dev/null 2>&1; then
    echo "✅ MySQL client found"
else
    echo "❌ MySQL client not found. Please install MySQL or MariaDB client"
    echo "   Ubuntu/Debian: sudo apt-get install mysql-client"
    echo "   CentOS/RHEL: sudo yum install mysql"
    echo "   macOS: brew install mysql-client"
    exit 1
fi

echo ""
echo "📦 Building the project..."
mvn clean package -DskipTests

# Check if build was successful
if [ $? -eq 0 ]; then
    echo "✅ Build successful!"
    echo ""
    
    echo "🔧 MySQL Configuration"
    echo "======================"
    echo ""
    echo "Before running the service with MySQL, please ensure:"
    echo "1. MySQL server is running"
    echo "2. Database 's3proxy' exists (or will be auto-created)"
    echo "3. Set the following environment variables:"
    echo ""
    echo "   export SPRING_PROFILES_ACTIVE=mysql"
    echo "   export MYSQL_USERNAME=your_username"
    echo "   export MYSQL_PASSWORD=your_password"
    echo ""
    
    # Check for environment variables
    if [ -z "$MYSQL_USERNAME" ] || [ -z "$MYSQL_PASSWORD" ]; then
        echo "⚠️  MySQL credentials not found in environment variables"
        echo "   Using default values - you may need to adjust them"
        echo ""
        export MYSQL_USERNAME=${MYSQL_USERNAME:-root}
        export MYSQL_PASSWORD=${MYSQL_PASSWORD:-}
        export SPRING_PROFILES_ACTIVE=mysql
        
        echo "   Current settings:"
        echo "   - Username: $MYSQL_USERNAME"
        echo "   - Password: [${MYSQL_PASSWORD:+SET}${MYSQL_PASSWORD:-NOT SET}]"
        echo ""
    else
        echo "✅ MySQL credentials found in environment"
        export SPRING_PROFILES_ACTIVE=mysql
    fi
    
    echo "🗄️  Database Setup Commands"
    echo "=========================="
    echo "If you need to create the database and user, run:"
    echo ""
    echo "mysql -u root -p << EOF"
    echo "CREATE DATABASE IF NOT EXISTS s3proxy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    echo "CREATE USER IF NOT EXISTS 's3proxy'@'%' IDENTIFIED BY 'secure_password';"
    echo "GRANT ALL PRIVILEGES ON s3proxy.* TO 's3proxy'@'%';"
    echo "FLUSH PRIVILEGES;"
    echo "EOF"
    echo ""
    
    echo "🏃‍♂️ Starting S3-Compatible API Service with MySQL..."
    echo "Service will be available at: http://localhost:8080"
    echo ""
    echo "📊 Database features:"
    echo "  ✅ Automatic table creation with optimized indexes"
    echo "  ✅ Connection pooling for performance"
    echo "  ✅ UTF-8 character set support"
    echo "  ✅ Foreign key constraints for data integrity"
    echo ""
    
    echo "🔗 S3-Compatible endpoints:"
    echo "  PUT    /{bucket}/{key}"
    echo "  GET    /{bucket}/{key}"
    echo "  DELETE /{bucket}/{key}"
    echo "  HEAD   /{bucket}"
    echo "  HEAD   /{bucket}/{key}"
    echo ""
    
    echo "💡 Make sure you also have MinIO running:"
    echo "  - MINIO_ENDPOINT=${MINIO_ENDPOINT:-http://localhost:9000}"
    echo "  - MINIO_ACCESS_KEY=${MINIO_ACCESS_KEY:-minioadmin}"
    echo "  - MINIO_SECRET_KEY=${MINIO_SECRET_KEY:-minioadmin}"
    echo ""
    
    echo "📚 See MYSQL_CONFIGURATION.md for detailed MySQL setup instructions"
    echo ""
    echo "🔄 To switch back to H2 database:"
    echo "   unset SPRING_PROFILES_ACTIVE"
    echo "   # or export SPRING_PROFILES_ACTIVE=h2"
    echo ""
    echo "Press Ctrl+C to stop the service"
    echo "Starting in 3 seconds..."
    sleep 3
    
    # Run the application with MySQL profile
    java -jar target/s3-proxy-java-0.1.0.jar
else
    echo "❌ Build failed! Please check the errors above."
    exit 1
fi