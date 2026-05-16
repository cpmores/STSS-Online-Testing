# API Test Commands

以下命令基于当前后端实现整理，默认不需要任何鉴权 Header，只通过请求体中的 `teacherId` / `studentId` 传递业务身份。

## 1. 预设变量

```bash
export BASE=http://localhost:8080/api/ot/v1
TEACHER_ID=9001
STUDENT_ID=9002
COURSE_ID=101
QUESTION_ID=1
EXAM_ID=1
RECORD_ID=1
```

## 2. 题库管理

### 2.1 添加题目 `add_a_question`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"add_a_question",
    "data":{
      "teacherId":9001,
      "courseId":101,
      "type":1,
      "stem":"软件生命周期的第一个阶段是？",
      "options":["A. 需求分析","B. 概要设计","C. 详细设计","D. 编码"],
      "answer":"A",
      "difficulty":1,
      "knowledgePoints":["软件工程基础"]
    }
  }'
```

### 2.2 修改题目 `update_a_question`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"update_a_question",
    "data":{
      "teacherId":9001,
      "id":1,
      "courseId":101,
      "type":1,
      "stem":"软件生命周期的起始阶段是？",
      "options":["A. 需求分析","B. 概要设计","C. 详细设计","D. 编码"],
      "answer":"A",
      "difficulty":1,
      "knowledgePoints":["软件工程基础","生命周期"]
    }
  }'
```

### 2.3 删除题目 `delete_a_question`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"delete_a_question",
    "data":{
      "teacherId":9001,
      "id":1,
      "force":true
    }
  }'
```

### 2.4 获取单题 `get_a_question`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"get_a_question",
    "data":{
      "teacherId":9001,
      "id":1
    }
  }'
```

### 2.5 查询题库 `query_question_bank`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"query_question_bank",
    "data":{
      "teacherId":9001,
      "courseId":101,
      "type":1,
      "difficulty":1,
      "keyword":"生命周期",
      "knowledgePoints":["软件工程基础"],
      "current":1,
      "size":10
    }
  }'
```

### 2.6 Excel 导入题库 `import_questions_by_excel`
* 要求Excel具有下面的所有属性:`课程ID` `题型(1单选/2是非)` `题干` `选项(JSON数组格式)` `标准答案` `难度(1-3)` `知识点(JSON数组格式)`，格式可参考本目录下的`questions.xlsx`

```bash
curl -X POST "$BASE/actions/question-bank/import" \
  -F "teacherId=9001" \
  -F "file=@./questions.xlsx"
```

## 3. 试卷与考试配置

### 3.1 手工组卷 `create_exam_paper`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"create_exam_paper",
    "data":{
      "teacherId":9001,
      "courseId":101,
      "title":"软件工程测试一",
      "totalScore":100,
      "durationMins":90,
      "passScore":60,
      "allowedAttempts":1,
      "generateMode":"manual",
      "questionIds":[1,2,3],
      "questionScores":[30,30,40]
    }
  }'
```

### 3.2 更新试卷 `update_exam_paper`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"update_exam_paper",
    "data":{
      "teacherId":9001,
      "id":1,
      "courseId":101,
      "title":"软件工程测试一-修订版",
      "totalScore":100,
      "durationMins":100,
      "passScore":60,
      "allowedAttempts":2,
      "generateMode":"manual",
      "questionIds":[1,2,3],
      "questionScores":[20,30,50]
    }
  }'
```

### 3.3 自动组卷 `generate_exam_paper`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"generate_exam_paper",
    "data":{
      "teacherId":9001,
      "courseId":101,
      "title":"软件工程自动组卷",
      "totalScore":100,
      "durationMins":60,
      "passScore":60,
      "allowedAttempts":1,
      "generateMode":"auto",
      "autoRules":{
        "singleChoiceCount":4,
        "trueFalseCount":2,
        "singleChoiceScore":20,
        "trueFalseScore":10,
        "targetDifficulty":1,
        "knowledgePoints":["软件工程基础"]
      }
    }
  }'
```

### 3.4 删除试卷 `delete_exam_paper`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"delete_exam_paper",
    "data":{
      "teacherId":9001,
      "id":1
    }
  }'
```

### 3.5 发布试卷 `publish_exam_paper`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"publish_exam_paper",
    "data":{
      "teacherId":9001,
      "id":1,
      "validStartTime":"2026-04-27T09:00:00.000+08:00",
      "validEndTime":"2026-12-31T23:59:59.000+08:00",
      "allowedAttempts":2,
      "scoringStrategy":"AUTO_GRADE"
    }
  }'
```

### 3.6 撤回试卷 `withdraw_exam_paper`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"withdraw_exam_paper",
    "data":{
      "teacherId":9001,
      "id":1
    }
  }'
```

### 3.7 页式查询试卷 `query_exam_papers`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"query_exam_papers",
    "data":{
      "teacherId":9001,
      "courseId":101,
      "current":1,
      "size":10
    }
  }'
```

### 3.8 教师预览试卷 `preview_exam_paper`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"preview_exam_paper",
    "data":{
      "teacherId":9001,
      "id":1
    }
  }'
```

### 3.9 学生获取考试试卷 `get_exam_paper_for_student`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"get_exam_paper_for_student",
    "data":{
      "id":1
    }
  }'
```

### 3.10 开放成绩 `open_exam_score`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"open_exam_score",
    "data":{
      "teacherId":9001,
      "id":1
    }
  }'
```

### 3.11 开放答案 `open_exam_answer`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"open_exam_answer",
    "data":{
      "teacherId":9001,
      "id":1
    }
  }'
```

### 3.12 获取统计 `get_exam_stats`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"get_exam_stats",
    "data":{
      "teacherId":9001,
      "id":1
    }
  }'
```

### 3.13 导出成绩 Excel `export_exam_scores`

```bash
curl -X POST "$BASE/actions/exams/export" \
  -H "Content-Type: application/json" \
  -d '{
    "teacherId":9001,
    "id":1
  }' \
  -o exam_scores.xlsx
```

## 4. 学生考试流程

### 4.1 开始考试 `begin_an_exam`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"begin_an_exam",
    "data":{
      "studentId":9002,
      "id":1
    }
  }'
```

### 4.2 保存进度 `save_exam_progress`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"save_exam_progress",
    "data":{
      "studentId":9002,
      "examId":1,
      "recordId":1,
      "answers":[
        {"questionId":1,"studentAnswer":"A"},
        {"questionId":2,"studentAnswer":"True"}
      ]
    }
  }'
```

### 4.3 提交答卷 `submit_exam_answers`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"submit_exam_answers",
    "data":{
      "studentId":9002,
      "examId":1,
      "recordId":1,
      "answers":[
        {"questionId":1,"studentAnswer":"A"},
        {"questionId":2,"studentAnswer":"True"}
      ]
    }
  }'
```

### 4.4 学生查看自己作答记录 `get_exam_record_review`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"get_exam_record_review",
    "data":{
      "studentId":9002,
      "recordId":1
    }
  }'
```

### 4.5 教师查看学生作答记录 `get_exam_record_review`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"get_exam_record_review",
    "data":{
      "teacherId":9001,
      "recordId":1
    }
  }'
```

### 4.6 学生查看自己的考试记录列表 `list_my_exam_records`

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"list_my_exam_records",
    "data":{
      "studentId":9002,
      "current":1,
      "size":10
    }
  }'
```

## 5. 建议联调顺序

1. `add_a_question`
2. `preview_exam_paper`
3. `create_exam_paper` 或 `generate_exam_paper`
4. `preview_exam_paper`
5. `publish_exam_paper`
6. `begin_an_exam`
7. `save_exam_progress`
8. `submit_exam_answers`
9. `get_exam_record_review`
10. `open_exam_score` / `open_exam_answer`
11. `get_exam_stats`
12. `export_exam_scores`

## 6. 关键异常场景回归

### 6.1 删除后的题目不可再查询

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"get_a_question",
    "data":{
      "teacherId":9001,
      "id":1
    }
  }'
```

预期：
`code=404`
`message=题目不存在或已删除`

### 6.2 删除后的题目不可参与组卷

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"create_exam_paper",
    "data":{
      "teacherId":9001,
      "courseId":101,
      "title":"删除题目后组卷校验",
      "totalScore":100,
      "durationMins":90,
      "passScore":60,
      "allowedAttempts":1,
      "generateMode":"manual",
      "questionIds":[1],
      "questionScores":[100]
    }
  }'
```

预期：
`code=404`
`message` 包含 `题目不存在或已删除`

### 6.3 非法题目 ID 不可组卷

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"create_exam_paper",
    "data":{
      "teacherId":9001,
      "courseId":101,
      "title":"非法题目ID校验",
      "totalScore":100,
      "durationMins":90,
      "passScore":60,
      "allowedAttempts":1,
      "generateMode":"manual",
      "questionIds":[20000],
      "questionScores":[100]
    }
  }'
```

预期：
`code=404`
`message` 包含 `以下题目不存在或已删除`

### 6.4 教师可预览未发布试卷

先创建草稿试卷，不要发布，然后执行：

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"preview_exam_paper",
    "data":{
      "teacherId":9001,
      "id":1
    }
  }'
```

预期：
`code=200`
返回试卷标题、时长和题目列表

### 6.5 已发布试卷不能删除

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"delete_exam_paper",
    "data":{
      "teacherId":9001,
      "id":1
    }
  }'
```

预期：
`code=409`
`message=已发布试卷不能删除，请先撤回后再处理`

### 6.6 草稿或已撤回试卷可以删除

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"delete_exam_paper",
    "data":{
      "teacherId":9001,
      "id":1
    }
  }'
```

预期：
`code=200`
`data.deleted=true`

### 6.7 交卷时不能提交不属于试卷的题目

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"submit_exam_answers",
    "data":{
      "studentId":9002,
      "examId":1,
      "recordId":1,
      "answers":[
        {"questionId":999999,"studentAnswer":"A"}
      ]
    }
  }'
```

预期：
`code=422`
`message` 包含 `不属于当前试卷`

## 7. 日志使用与查看

### 7.1 当前日志行为

当前 Java 后端的日志有两条输出链路：

- gRPC 日志上报：按 `shared/proto/logger.proto` 中的 `LoggerService.Log` 单条日志协议发送
- MySQL 镜像落库：无论 gRPC logger 是否可达，都会尽量向 `action_log` 表写入一条镜像日志

日志由后端自动记录，不需要额外调用日志接口。当前统一入口相关操作在执行成功或失败后都会自动写日志，包括：

- `POST /api/ot/v1/actions`
- `POST /api/ot/v1/actions/question-bank/import`
- `POST /api/ot/v1/actions/exams/export`

### 7.2 日志配置

默认配置在 `src/main/resources/application.yml`：

```yaml
logger:
  grpc:
    host: localhost
    port: 50061
    plaintext: true
    timeout-ms: 500
    api-service-name: apiserver-service
    proctor-service-name: proctor-controller-service
```

说明：

- 如果本机启动了 Go 侧 logger 服务并监听 `localhost:50061`，则会尝试 gRPC 投递
- 如果 gRPC logger 未启动，请求本身不会失败，但 `action_log.grpc_delivered` 会记为 `0`

### 7.3 最小验证方法

先启动 Java 后端，然后发一条最小请求：

```bash
curl -X POST "http://localhost:8080/api/ot/v1/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"unknown_action",
    "data":{
      "teacherId":9001
    }
  }'
```

预期响应：

```json
{"code":400,"message":"未注册的 action: unknown_action","data":null}
```

随后查看数据库中的镜像日志：

```bash
docker exec stss-mysql mysql -uroot -proot stss_testing -e "
SELECT id, service, operation_id, trace_id, user_id, status_code,
       grpc_delivered, grpc_error,
       JSON_UNQUOTE(JSON_EXTRACT(string_fields, '$.action')) AS action
FROM action_log
ORDER BY id DESC
LIMIT 10;"
```

如果当前没有启动 gRPC logger，预期会看到：

- `service=apiserver-service`
- `operation_id=4001`
- `status_code=400`
- `grpc_delivered=0`
- `action=unknown_action`
- `grpc_error` 中包含 `gRPC logger unavailable`

### 7.4 常用查看 SQL

查看最新 20 条日志：

```bash
docker exec stss-mysql mysql -uroot -proot stss_testing -e "
SELECT id, create_time, service, operation_id, user_id, status_code,
       grpc_delivered, entity_type, entity_id, message
FROM action_log
ORDER BY id DESC
LIMIT 20;"
```

只看失败日志：

```bash
docker exec stss-mysql mysql -uroot -proot stss_testing -e "
SELECT id, create_time, service, operation_id, user_id, status_code,
       grpc_delivered, grpc_error, error_message
FROM action_log
WHERE status_code <> 200
ORDER BY id DESC
LIMIT 20;"
```

按 `trace_id` 查看单次请求链路：

```bash
docker exec stss-mysql mysql -uroot -proot stss_testing -e "
SELECT id, service, operation_id, trace_id, user_id, method, path,
       status_code, duration_ms, message
FROM action_log
WHERE trace_id = '替换为实际 trace_id'
ORDER BY id ASC;"
```

查看附加字段：

```bash
docker exec stss-mysql mysql -uroot -proot stss_testing -e "
SELECT id, string_fields, int_fields
FROM action_log
ORDER BY id DESC
LIMIT 10;"
```

### 7.5 字段说明

- `service`：日志来源服务，当前主要是 `apiserver-service` 或 `proctor-controller-service`
- `operation_id`：动作编号；已注册动作使用固定编号，未知动作或异常使用错误编号
- `trace_id`：单次请求追踪标识，由请求拦截器自动生成
- `status_code`：这里记录的是后端统一响应体中的业务码，不是 HTTP 状态码
- `grpc_delivered`：`1` 表示已成功投递到 gRPC logger，`0` 表示未成功投递
- `grpc_error`：gRPC 投递失败原因的截断文本
- `string_fields`：额外字符串字段，常见包含 `action`、`operatorRole`、`requestPayload`、`responsePreview`
- `int_fields`：额外整数字段，常见包含 `resultCode`、`success`、`courseId`、`examId`、`recordId`

### 7.6 当前使用建议

- 开发联调阶段，即使不启动 Go 侧 logger，也可以直接通过 `action_log` 排查问题
- 如果要验证 proto 链路，再单独启动监听 `50061` 的 logger 服务，并观察 `grpc_delivered` 是否变为 `1`
- 若发现请求已返回但 `action_log` 没有新增记录，应优先检查应用控制台中是否存在 `Failed to persist action log mirror`
