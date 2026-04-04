#!/bin/bash
# Build all modules from the parent, then start with Docker Compose

set -e

echo "==> Building all Maven modules..."
mvn clean package -DskipTests

echo "==> Starting Docker Compose stack..."
docker-compose up --build -d

echo ""
echo "Services started:"
echo "  Onboarding API  -> http://localhost:8080"
echo "  SFTP Server     -> sftp://localhost:2222"
echo "  SFTP Control    -> http://localhost:8081/internal/health"
echo "  FTP Server      -> ftp://localhost:21"
echo "  FTP Control     -> http://localhost:8082/internal/health"
echo "  RabbitMQ UI     -> http://localhost:15672 (guest/guest)"
echo ""
echo "Quick start:"
echo "  1. Register: POST http://localhost:8080/api/auth/register"
echo "  2. Login:    POST http://localhost:8080/api/auth/login"
echo "  3. Create SFTP account: POST http://localhost:8080/api/accounts"
echo "  4. Connect:  sftp -P 2222 <username>@localhost"
