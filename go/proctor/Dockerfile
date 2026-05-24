# 使用多阶段构建来创建一个最小的镜像

# 1. 构建阶段
FROM golang:1.26-alpine AS builder

# 设置工作目录
WORKDIR /app

# 复制 go.mod 和 go.sum 文件
COPY go.mod go.sum ./

# 下载依赖
RUN go mod download

# 复制所有源代码
COPY . .

# 构建 Go 应用
# -o /app/server: 指定输出的二进制文件名为 server
# CGO_ENABLED=0: 禁用 CGO，以便静态链接
# GOOS=linux: 指定目标操作系统为 Linux
RUN CGO_ENABLED=0 GOOS=linux go build -o /app/server .

# 2. 运行阶段
FROM alpine:latest

# 更新基础镜像中的所有包以修复已知的漏洞
RUN apk update && apk upgrade

# 设置工作目录
WORKDIR /app

# 从构建阶段复制编译好的二进制文件
COPY --from=builder /app/server .

# 暴露端口 (Gin 默认使用 8080)
EXPOSE 8080

# 运行应用
CMD ["/app/server"]
