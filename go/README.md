# STSS Online Testing - Go Backend

本项目是在线测试系统的 Go 语言后端实现，主要负责学生答题系统的相关功能。

## 构建 (Build)

### 使用 Docker (推荐)

我们推荐使用 Docker 来构建和运行此服务，以确保环境一致性。

1.  **构建并运行服务**:
    在项目根目录下，运行以下命令：
    ```bash
    docker-compose up --build go-app
    ```
    这将会：
    -   根据 `go/Dockerfile` 构建 Go 应用的 Docker 镜像。
    -   启动名为 `go-app` 的服务。
