#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
GO_DIR="$(dirname "$PROJECT_DIR")"  # go/ 目录，包含 logger 和 proctor

cd "$PROJECT_DIR"

echo "============================================"
echo "  Proctor 集成测试"
echo "============================================"

# 1. 构建镜像（从 go/ 目录构建以包含 logger 依赖）
echo ""
echo "[1/3] 构建 proctor 和 testclient 镜像..."
docker build -t ot-proctor:latest \
    -f Dockerfile.test --target proctor \
    "$GO_DIR"

docker build -t ot-proctor-testclient:latest \
    -f Dockerfile.test --target testclient \
    "$GO_DIR"

# 2. 启动测试环境
echo ""
echo "[2/3] 启动测试环境..."
docker compose -f docker-compose.test.yml up -d

# 等待 proctor 就绪
echo "等待 proctor 就绪..."
for i in $(seq 1 10); do
    if docker compose -f docker-compose.test.yml ps proctor | grep -q "Up"; then
        break
    fi
    sleep 1
done

if ! docker compose -f docker-compose.test.yml ps proctor | grep -q "Up"; then
    echo "ERROR: proctor 启动失败"
    docker compose -f docker-compose.test.yml logs proctor
    docker compose -f docker-compose.test.yml down -v
    exit 1
fi

# 3. 运行测试客户端
echo ""
echo "[3/3] 运行测试..."
docker run --rm --network container:proctor-test-exam ot-proctor-testclient:latest
TEST_RESULT=$?

# 清理
echo ""
echo "清理测试环境..."
docker compose -f docker-compose.test.yml down -v

echo ""
if [ $TEST_RESULT -eq 0 ]; then
    echo "测试全部通过!"
else
    echo "存在测试失败，请查看上方输出"
fi
exit $TEST_RESULT
