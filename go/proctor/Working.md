# Proctor 模块

## 概述

Proctor 是 STSS 在线测试系统的 **学生答题引擎**，使用 Go 语言实现。负责管理考试会话、实时答题通信、答案收集和交卷流程。

## 架构

```
┌──────────┐    ┌──────────────┐    ┌──────────┐    ┌──────────┐
│  前端     │───▶│  Controller  │───▶│  Proctor  │◀──▶│  Redis   │◀─── Java
│ (浏览器)  │    │  (容器编排)   │    │  (答题引擎) │    │          │
└──────────┘    └──────────────┘    └──────────┘    └──────────┘
     │               │                    │               │
     │          Docker API           WebSocket         grading:pending
     │          (创建容器)           HTTP REST         队列通知评分
     │                                                  │
     └──────────────────────────────────────────────────┘
                      试卷数据、答案、会话
```

- **Controller**：为每场考试创建独立 proctor 容器，容器复用，过期自动清理
- **Proctor**：处理一场考试的所有学生答题请求，WebSocket 长连接 + HTTP 兜底
- **Redis**：试卷快照、会话状态、答案暂存、评分队列
- **Java**：下发试卷到 Redis，消费评分队列完成评分入库

## 职责边界

| Proctor 负责 | Proctor 不负责 |
|-------------|---------------|
| 考试会话管理（开始/答题/交卷） | 题库管理 |
| WebSocket 实时通信（心跳/答案保存/状态推送） | 试卷管理 |
| 答案收集到 Redis | 评分（Java 做） |
| 倒计时和强制交卷 | 成绩统计分析 |
| 创建/回收 Docker 容器 | 权限校验 |

## 目录结构

```
go/proctor/
├── cmd/
│   ├── controller/        # Controller 容器编排服务
│   │   ├── main.go        # HTTP 入口、路由、中间件
│   │   └── registry.go    # Docker 容器注册表
│   ├── proctor/           # Proctor 答题引擎
│   │   ├── main.go        # HTTP 入口、路由、中间件
│   │   ├── session.go     # Redis 会话管理
│   │   └── ws.go          # WebSocket 协议处理
│   └── testclient/        # 集成测试客户端
│       └── main.go
├── modules/
│   └── exam.go            # 数据模型定义
├── pkg/
│   └── loggerclient/      # logger gRPC 客户端
├── test/
│   ├── run.sh             # 一键测试脚本
│   └── init-data.sh       # Redis 测试数据初始化
├── API.md                 # API 文档
├── Java侧需求文档.md        # 对 Java 侧的需求
├── docker-compose.test.yml # 测试环境
├── Dockerfile.test        # 测试镜像
├── Dockerfile             # 单服务镜像（待更新）
├── Dockerfile.multi        # 多阶段构建（Controller + Proctor）
└── go.mod
```

## Controller

HTTP 端口：**8080**

为每场考试动态创建 proctor 容器。同一 examId 的重复请求复用已有容器。

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/proctor/v1/start?examId=` | 启动或获取 proctor 容器 |
| GET | `/api/proctor/v1/status/:examId` | 查询容器状态 |
| DELETE | `/api/proctor/v1/stop/:examId` | 停止容器 |

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LOGGER_ADDR` | — | logger gRPC 地址 |
| `REDIS_ADDR` | `localhost:6379` | Redis 地址（同时注入到子容器） |
| `PROCTOR_IMAGE` | `ot-proctor:latest` | proctor 镜像名 |

## Proctor

HTTP 端口：**9090**

每个 proctor 容器处理**一场考试**的所有学生请求。启动时从 Redis 加载试卷快照，维护考试会话直到结束。

### 核心功能

1. **会话管理**：学生通过 WS 连接时从 Redis 创建/恢复会话，截止时间取 `min(首次进入时间 + durationMins, exam.validEndTime)`
2. **答案收集**：WS 实时保存 + HTTP 兜底，答案写入 Redis Hash；超时后拒绝继续保存
3. **状态推送**：每次保存后同步剩余时间和已答题数
4. **主动交卷**：学生发送 submit 消息，标记会话完成并入评分队列
5. **强制交卷**：后台每 10 秒扫描过期会话，自动标记并通知学生；学生若在超时后重连，会立即触发强制交卷
6. **心跳保活**：服务端每 30 秒发送 ping，60 秒无 pong 断连

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/ws?studentId=&recordId=` | WebSocket 考试通道 |
| POST | `/api/proctor/v1/answers/save` | 单题保存（HTTP 兜底） |
| POST | `/api/proctor/v1/answers/batch` | 批量保存（断线恢复） |
| POST | `/api/proctor/v1/answers/submit` | 主动交卷 |
| GET | `/api/proctor/v1/session/:studentId` | 获取会话状态 |

### WebSocket 消息类型

| type | 方向 | 说明 |
|------|------|------|
| `ping` | 双向 | 心跳 |
| `pong` | 双向 | 心跳响应 |
| `save_answer` | 客户端→服务端 | 保存答案 |
| `ack` | 服务端→客户端 | 保存确认 |
| `status` | 服务端→客户端 | 状态同步（剩余时间、已答题数） |
| `submit` | 客户端→服务端 | 主动交卷 |
| `submitted` | 服务端→客户端 | 交卷确认 |
| `exam_expired` | 服务端→客户端 | 强制交卷通知 |

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `EXAM_ID` | — | 当前服务的考试 ID（Controller 注入） |
| `REDIS_ADDR` | `localhost:6379` | Redis 地址 |
| `LOGGER_ADDR` | — | logger gRPC 地址 |

## Redis 数据结构

| Key | 类型 | 写入方 | 读取方 | 说明 |
|-----|------|--------|--------|------|
| `exam:{examId}:paper` | String (JSON) | Java | Controller, Proctor | 试卷快照（题目、选项、分值） |
| `exam:{examId}:proctor` | String (JSON) | Controller | 前端/网关 | proctor WS 端点 |
| `exam:{examId}:session:{studentId}` | Hash | Proctor | Proctor | 会话：recordId, startTime, expireTime, status |
| `exam:{examId}:answers:{studentId}` | Hash | Proctor | Java | 学生答案：questionId→answer |
| `grading:pending` | List | Proctor | Java | 待评分队列，交卷后 RPUSH Base64 编码的 protobuf `GradingTask` |

## 考试流程

```
1. Java begin_an_exam
   → 写 exam:{examId}:paper 到 Redis
   → 创建 student_exam_record (MySQL)
   → 返回试卷 + recordId 给前端

2. 前端调 Controller POST /start?examId=
   → Controller 创建/复用 proctor 容器
   → 注入 EXAM_ID, REDIS_ADDR
   → 返回 WS 端点

3. 学生连接 WS /ws?studentId=&recordId=
   → Proctor 从 Redis 读试卷快照
   → 创建 exam:{examId}:session:{studentId}
   → 推送初始状态（剩余时间、已答题数）
   → 循环：心跳 + 保存答案 + 状态同步

4. 交卷（主动/超时）
   → 标记 session 为 submitted/expired
   → 答案写入 exam:{examId}:answers:{studentId}
   → gRPC 成功时由 Java 直接评分
   → gRPC 不可用时 RPUSH grading:pending(Base64 protobuf GradingTask)
   → 通知学生（submitted / exam_expired）

5. Java 消费 grading:pending
   → leftPop grading:pending（空队列时短暂 sleep 轮询）
   → 从 Redis 读答案 + 试卷快照
   → 评分 → 写 MySQL (student_exam_record, student_exam_answer)
   → 清理 Redis keys
```

## 测试

```bash
cd go/proctor && bash test/run.sh
```

测试覆盖 8 个场景：（通过 WS 创建会话后）HTTP 单题保存、批量保存、会话恢复、WS 连接与状态推送、心跳 ping/pong、答案保存与确认、WS 主动交卷、HTTP 主动交卷。
