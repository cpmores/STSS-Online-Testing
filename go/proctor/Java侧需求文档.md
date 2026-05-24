# Java API Server 需求文档

## 背景

Go Proctor 负责在线考试的实时答题管理（WebSocket 通信、答案实时保存、考试计时、强制交卷）。Redis 作为 Go Proctor 和 Java API Server 之间的数据桥梁。

**职责划分：**

| 模块 | 职责 |
|------|------|
| Go Proctor | 考试会话管理、WebSocket 实时通信、答案收集到 Redis、强制交卷计时 |
| Java API Server | 题库管理、试卷管理、成绩统计、权限校验、评分入库 |
| Redis | 考试会话临时状态、试卷快照缓存、学生答案暂存 |
| MySQL | 持久化存储（现有表不变） |

---

## 需求一：开始考试时缓存试卷快照到 Redis

### 触发时机

`begin_an_exam` action 执行时，在现有逻辑（创建 `student_exam_record`、返回试卷）的基础上，**额外将试卷快照写入 Redis**。

### 写入内容

试卷快照应包含评卷所需的全部信息（正确答案、每题分值），供 Go Proctor 创建考试会话使用，也供后续交卷评分时使用。

### Redis Key 约定

```
exam:{examId}:paper → String (JSON)
```

### JSON 结构

```json
{
  "examId": 1001,
  "title": "期中考试",
  "durationMins": 60,
  "totalScore": 100,
  "questions": [
    {
      "questionId": 1,
      "type": 1,
      "stem": "1 + 1 = ?",
      "options": ["1", "2", "3", "4"],
      "answer": "2",
      "score": 10,
      "sortOrder": 1
    }
  ]
}
```

### TTL

建议设为考试结束后 24 小时，便于异常情况数据恢复。

```
TTL = (考试有效截止时间 - 当前时间) + 24h
```

---

## 需求二：新增交卷评分 action `commit_exam_result`

### 概述

Go Proctor 在学生主动交卷或考试超时强制交卷后，调用 Java 的此接口完成评分与持久化。

### 请求

```
POST /api/ot/v1/actions
```

```json
{
  "action": "commit_exam_result",
  "data": {
    "examId": 1001,
    "studentId": 20230001,
    "recordId": 456
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| examId | Long | 是 | 试卷 ID |
| studentId | Long | 是 | 学生 ID |
| recordId | Long | 否 | 已有的考试记录 ID，若传入则复用，否则新建 |

### Java 处理流程

1. 根据 `studentId` 和 `examId` 从 MySQL 查询或创建 `student_exam_record`（状态为考试中 `0`）
2. 从 Redis 读取学生答题数据：
   - Key: `exam:{examId}:answers:{studentId}` → Hash `{questionId: answer}`
3. 从 Redis 读取试卷快照：
   - Key: `exam:{examId}:paper` → JSON（含正确答案和分值）
4. 逐题评分：
   - 学生答案与标准答案比对（忽略大小写和首尾空格，trim + equalsIgnoreCase）
   - 正确 → 得分 = 题目分值，`isCorrect = 1`
   - 错误/未作答 → 得分 = 0，`isCorrect = 0`
5. 写入 MySQL：
   - 更新 `student_exam_record`：`status = 1`，`total_score = 总分`，`submit_time = 当前时间`
   - 写入/更新 `student_exam_answer` 明细（每题一条，字段：`recordId`, `questionId`, `studentAnswer`, `isCorrect`, `score`）
6. 清理 Redis：
   - 删除 `exam:{examId}:answers:{studentId}`
   - 删除 `exam:{examId}:session:{studentId}`（如有）

### 响应

```json
{
  "code": 200,
  "message": "交卷评分完成",
  "data": {
    "recordId": 456,
    "totalScore": 85,
    "scoreVisible": false,
    "answerVisible": false
  }
}
```

---

## 需求三：Redis 数据只读（供成绩查询参考）

> **优先级：低**，可在后续迭代中实现。

当教师开放成绩/答案后，现有查询逻辑已从 MySQL 读取，无需改动。Go Proctor 在交卷后即清理 Redis，所以 Java 现有查询无需变更。

---

## Redis Key 约定汇总

| Key | 类型 | 写入方 | 读取方 | 说明 |
|-----|------|--------|--------|------|
| `exam:{examId}:paper` | String (JSON) | Java | Go、Java | 试卷快照，含题目、答案、分值 |
| `exam:{examId}:session:{studentId}` | Hash | Go | Go、Java | 考试会话：recordId, startTime, expireTime, status |
| `exam:{examId}:answers:{studentId}` | Hash | Go | Java | 学生答案：field=questionId, value=answer |

---

## 对其他模块的影响

- **ProctorFacade**：`begin_an_exam` 需增加 Redis 写入逻辑
- **ProctorFacade**：新增 `commit_exam_result` action 处理
- **ConfigManager**：新增 `commit_exam_result` 路由分发
- **application.yml**：新增 Redis 连接配置
