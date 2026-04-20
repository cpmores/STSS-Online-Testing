#!/bin/bash

echo "=== Testing gRPC Logger Service ==="

echo "1. Listing all services..."
grpcurl -plaintext localhost:50051 list

echo -e "\n2. Listing methods..."
grpcurl -plaintext localhost:50051 list loggerServer.v1.LoggerService

echo -e "\n3. Sending INFO log..."
grpcurl -plaintext -d '{
  "level": "LOG_LEVEL_INFO",
  "message": "Test info message",
  "service": "test-service",
  "traceId": "trace-001",
  "userId": "user-001",
  "method": "GET",
  "path": "/api/test",
  "statusCode": 200,
  "durationMS": 100
}' localhost:50051 loggerServer.v1.LoggerService/Log

echo -e "\n4. Sending ERROR log..."
grpcurl -plaintext -d '{
  "level": "LOG_LEVEL_ERROR",
  "message": "Test error message",
  "service": "test-service",
  "errorMessage": "something went wrong",
  "stackTrace": "main.go:45"
}' localhost:50051 loggerServer.v1.LoggerService/Log

echo -e "\n5. Testing validation (missing message)..."
grpcurl -plaintext -d '{
  "level": "LOG_LEVEL_INFO",
  "service": "test-service"
}' localhost:50051 loggerServer.v1.LoggerService/Log

echo -e "\n=== Test completed ==="