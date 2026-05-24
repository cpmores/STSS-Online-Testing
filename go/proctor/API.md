# Proctor 模块 API 文档

Base URL: `/api/proctor/v1`

---

## 一、Controller 接口

Controller 负责 proctor 容器的生命周期管理。

### POST /start

启动或获取指定考试的 proctor 容器。同一 examId 重复请求复用已有容器。

**请求**
```
POST /api/proctor/v1/start?examId=1001
```

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| examId | query | string | 是 | 试卷 ID |

**响应 `200`**
```json
{
  "message": "proctor started",
  "examId": "1001",
  "ws_endpoint": "ws://localhost:49153/ws"
}
```

**错误响应**
| 状态码 | 说明 |
|--------|------|
| 400 | 缺少 examId |
| 404 | 试卷快照未就绪（Java 尚未下发试卷到 Redis） |
| 410 | 考试已过期 |
| 500 | 容器创建失败 |

**兼容旧接口:** `POST /api/exam/v1/start-proctor`（参数与行为一致）

---

### GET /status/:examId

查询 proctor 容器运行状态。

**请求**
```
GET /api/proctor/v1/status/1001
```

**响应 `200`**
```json
{
  "examId": "1001",
  "wsEndpoint": "ws://localhost:49153/ws",
  "containerId": "abc123def456",
  "createdAt": "2026-05-24T10:00:00Z"
}
```

**错误响应**
| 状态码 | 说明 |
|--------|------|
| 404 | proctor 未创建且创建失败 |

---

### DELETE /stop/:examId

强制停止并删除 proctor 容器，同时清理 Redis 中的端点信息。

**请求**
```
DELETE /api/proctor/v1/stop/1001
```

**响应 `200`**
```json
{
  "message": "proctor stopped",
  "examId": "1001"
}
```

**错误响应**
| 状态码 | 说明 |
|--------|------|
| 404 | proctor 不存在 |
| 500 | 容器删除失败 |

---

## 二、Proctor 容器接口

Proctor 容器负责考试会话管理和实时答题。容器内部监听 `9090` 端口。

### GET /ws

WebSocket 考试通道，承载心跳保活和答案实时保存。

**连接**
```
ws://localhost:{hostPort}/ws?studentId=20230001&recordId=456
```

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| studentId | query | string | 是 | 学生 ID |
| recordId | query | int64 | 是 | Java 创建的考试记录 ID |

#### WebSocket 消息协议

所有消息均为 JSON，通过 `type` 字段区分。服务端每 30s 发送心跳 ping，60s 未收到 pong 则断连。

##### 心跳 Ping（双向）
```json
{ "type": "ping" }
```

##### 心跳 Pong（双向）
```json
{ "type": "pong", "serverTime": 1716900000 }
```

##### 保存答案（客户端 → 服务端）
```json
{
  "type": "save_answer",
  "questionId": 123,
  "answer": "A"
}
```

##### 保存确认（服务端 → 客户端）
```json
{ "type": "ack", "questionId": 123, "status": "saved" }
```

##### 状态同步（服务端 → 客户端）
每次保存答案后自动推送，初始连接时也推送一次。
```json
{
  "type": "status",
  "remainingSeconds": 1800,
  "answeredCount": 15
}
```

##### 主动交卷（客户端 → 服务端）
```json
{ "type": "submit" }
```

##### 交卷确认（服务端 → 客户端）
```json
{ "type": "submitted", "status": "ok" }
```

##### 强制交卷通知（服务端 → 客户端）
考试时间耗尽时推送，随后关闭连接。
```json
{
  "type": "exam_expired",
  "status": "time is up, exam submitted"
}
```

---

### POST /answers/save

HTTP 答案保存接口，作为 WebSocket 断开时的兜底方案。

**请求**
```
POST /api/proctor/v1/answers/save
Content-Type: application/json
```

```json
{
  "studentId": "20230001",
  "examId": "1001",
  "questionId": 1,
  "answer": "A"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| studentId | string | 是 | 学生 ID |
| examId | string | 是 | 试卷 ID |
| questionId | int64 | 是 | 题目 ID |
| answer | string | 是 | 学生答案 |

**响应 `200`**
```json
{ "message": "saved" }
```

---

### POST /answers/batch

批量恢复答案，用于 WebSocket 断线重连后同步本地缓存。

**请求**
```
POST /api/proctor/v1/answers/batch
Content-Type: application/json
```

```json
{
  "studentId": "20230001",
  "examId": "1001",
  "answers": [
    { "questionId": 1, "answer": "A" },
    { "questionId": 2, "answer": "True" }
  ]
}
```

**响应 `200`**
```json
{ "message": "batch saved", "savedCount": 2 }
```

---

### POST /answers/submit

学生主动交卷，标记会话结束并通知 Java 评分。

**请求**
```
POST /api/proctor/v1/answers/submit
Content-Type: application/json
```

```json
{
  "studentId": "20230001",
  "examId": "1001"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| studentId | string | 是 | 学生 ID |
| examId | string | 是 | 试卷 ID |

**响应 `200`**
```json
{ "message": "exam submitted" }
```

**错误响应**
| 状态码 | 说明 |
|--------|------|
| 400 | 参数缺失 |
| 409 | 会话不存在或已结束 |

---

### GET /session/:studentId

获取学生当前考试会话状态，用于断线重连后恢复答题进度。

**请求**
```
GET /api/proctor/v1/session/20230001
```

**响应 `200`**
```json
{
  "examId": "1001",
  "title": "期中考试",
  "remainingSeconds": 1740,
  "answers": {
    "1": "A",
    "2": "True"
  },
  "questions": [
    {
      "questionId": 1,
      "type": 1,
      "score": 10,
      "stem": "1 + 1 = ?",
      "options": ["1", "2", "3", "4"],
      "sortOrder": 1
    }
  ],
  "session": {
    "recordId": "456",
    "studentId": "20230001",
    "examId": "1001",
    "startTime": "2026-05-24T10:00:00+08:00",
    "expireTime": "2026-05-24T11:00:00+08:00",
    "status": "0"
  }
}
```

---

## 三、Redis Key 约定

| Key | 类型 | 写入方 | 读取方 | TTL | 说明 |
|-----|------|--------|--------|-----|------|
| `exam:{examId}:paper` | String (JSON) | Java | Controller, Proctor | 考试结束 + 24h | 试卷快照 |
| `exam:{examId}:proctor` | String (JSON) | Controller | 前端/网关 | 2h | proctor 端点信息 |
| `exam:{examId}:session:{studentId}` | Hash | Proctor | Proctor | 考试结束 + 1h | 考试会话元信息 |
| `exam:{examId}:answers:{studentId}` | Hash | Proctor | Java | 考试结束 + 1h | 学生答案 |
| `grading:pending` | List | Proctor | Java | — | 待评分队列，交卷后 RPUSH，Java BRPOP 消费 |

---

## 四、环境变量

### Controller

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `LOGGER_ADDR` | — | logger gRPC 地址 |
| `REDIS_ADDR` | `localhost:6379` | Redis 地址 |
| `PROCTOR_IMAGE` | `ot-proctor:latest` | proctor 容器镜像名 |

### Proctor

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `EXAM_ID` | `unknown` | 当前容器服务的考试 ID（由 Controller 注入） |
| `LOGGER_ADDR` | — | logger gRPC 地址 |
| `REDIS_ADDR` | `localhost:6379` | Redis 地址 |
