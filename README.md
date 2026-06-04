# STSS-Online-Testing
A subsystem for STSS (Smart Teaching Service System) , focused on a scalable, extensible and stable OT platform.

## 当前进展

## 1. 当前完成情况

- Java API、Go logger、Go proctor controller、动态 proctor 容器、MySQL、Redis 已全部容器化。
- 已通过 `cd java && ./mvnw test`，当前结果为 `Tests run: 14, Failures: 0, Errors: 0`。
- 已完成一次端到端联调：
  - `begin_an_exam` 会返回 `wsEndpoint`
  - `proctor-controller` 会动态拉起 `proctor-exam-<examId>`
  - Go proctor 可通过 gRPC 回调 Java `50080`
  - MySQL 中 `student_exam_record.status=1,total_score=10`，答案表正确落库

## 2. 前置条件

- 下面所有 `docker compose` 命令都在仓库根目录执行：`STSS-Online-Testing/`
- 本机已安装 Docker 和 Docker Compose v2
- 端口 `3306`、`6379`、`8080`、`8081`、`50051`、`50080` 未被占用
- 如果前端不是和 Docker 主机在同一台机器访问，需要先修改根目录 [docker-compose.yml](/home/yuki_noa/software_project/STSS-Online-Testing/docker-compose.yml) 中的 `PROCTOR_PUBLIC_HOST` 和 `PROCTOR_WS_SCHEME`
  当前默认值是 `localhost` 和 `ws`，只适合同机联调

## 3. 启动整个后端

```bash
docker compose build
docker compose up -d
docker compose ps
```

当前容器组织形式如下：

- `mysql`
- `redis`
- `logger-service`
- `java-api`
- `proctor-image-bootstrap`
  这是一次性启动容器，只负责预热 `stss-proctor:latest`
- `proctor-controller`
- `proctor-exam-<examId>`
  只有在学生真正开考后才会动态创建，并挂到 `stss-backend` 网络

常用检查命令：

```bash
docker compose logs -f java-api proctor-controller logger-service
docker ps --format '{{.Names}} {{.Status}}' | rg 'proctor-exam-|stss-online-testing'
```

## 4. Java 测试

测试 profile 已切到 [application-test.yml](/home/yuki_noa/software_project/STSS-Online-Testing/java/src/test/resources/application-test.yml)，默认连接 `localhost:3306/stss_testing`。

最简单的回归方式是先起 MySQL，再执行 Maven 测试：

```bash
docker compose up -d mysql
cd java && ./mvnw test
```

##  最小容器联调流程

### 1 预设变量

```bash
export BASE=http://localhost:8080/api/ot/v1
export CONTROLLER=http://localhost:8081
TEACHER_ID=91001
STUDENT_ID=91002
COURSE_ID=81001
```

```fish
export BASE=http://localhost:8080/api/ot/v1
export CONTROLLER=http://localhost:8081
set TEACHER_ID91001
set STUDENT_ID 91002
set COURSE_ID 81001
```

### 2 创建题目、试卷并开考

1. 调用 `2.1 add_a_question`，记录返回的 `data.id` 为 `QUESTION_ID`
2. 调用 `3.1 create_exam_paper`，记录返回的 `data.examId` 为 `EXAM_ID`
3. 调用 `3.5 publish_exam_paper`
4. 调用 `4.1 begin_an_exam`，记录返回的 `data.recordId` 和 `data.wsEndpoint`

`begin_an_exam` 在当前容器化架构下会同时完成四件事：

- Java 将试卷快照发布到 Redis
- Java 调用 `proctor-controller`
- controller 创建 `proctor-exam-<EXAM_ID>` 容器
- Java 将 `wsEndpoint` 返回给前端

答题时必须使用 `begin_an_exam` 返回的 `data.paper.questions[*].questionId`，不能手写或猜测题号。
也可以随时通过下面的接口核对当前试卷题号：

```bash
curl "http://localhost:<动态端口>/api/proctor/v1/session/<STUDENT_ID>"
```

### 3 用 WebSocket 建立会话

先用一次 WebSocket 连接建立学生会话，连接成功后收到首条 `status` 消息即可断开：

```bash
websocat -t "ws://localhost:<动态端口>/ws?studentId=<STUDENT_ID>&recordId=<RECORD_ID>"
```

### 4 直接走 Go proctor 的保存与交卷接口

```bash
curl -X POST "http://localhost:<动态端口>/api/proctor/v1/answers/save" \
  -H "Content-Type: application/json" \
  -d '{
    "studentId":"<STUDENT_ID>",
    "examId":"<EXAM_ID>",
    "questionId":<begin_an_exam 返回的 questionId>,
    "answer":"A"
  }'

curl -X POST "http://localhost:<动态端口>/api/proctor/v1/answers/submit" \
  -H "Content-Type: application/json" \
  -d '{
    "studentId":"<STUDENT_ID>",
    "examId":"<EXAM_ID>"
  }'
```

这里验证的是 Go -> Java 的真实回写链路：

- Go proctor 会先把答案写入 Redis
- 交卷时优先通过 `java-api:50080` 调用 Java gRPC `CommitExam`
- 如果 gRPC 不可用，才会降级写入 Redis `grading:pending`
- 如果 `questionId` 不属于当前试卷，保存接口会直接返回 `422`

### 5 联调校验

检查动态 proctor 是否创建：

```bash
docker ps --format '{{.Names}} {{.Status}}' | rg "proctor-exam-<EXAM_ID>"
```

检查学生作答记录：

```bash
curl -X POST "$BASE/actions" \
  -H "Content-Type: application/json" \
  -d '{
    "action":"get_exam_record_review",
    "data":{
      "studentId":<STUDENT_ID>,
      "recordId":<RECORD_ID>
    }
  }'
```

直接检查 MySQL 落库：

```bash
docker exec $(docker compose ps -q mysql) \
  mysql -N -uroot -proot stss_testing \
  -e "SELECT status,total_score FROM student_exam_record WHERE id=<RECORD_ID>; \
      SELECT question_id,score,is_correct FROM student_exam_answer WHERE record_id=<RECORD_ID>;"
```

如果 `submit` 返回成功后，`get_exam_record_review` 仍提示“该考试记录尚未交卷”，先检查：

```bash
docker compose logs --tail=100 java-api proctor-controller
docker compose exec -T redis redis-cli HGETALL exam:<EXAM_ID>:answers:<STUDENT_ID>
```

典型原因是 Redis 中保存了不属于当前试卷的题号，Java 会拒绝评分并保留 `student_exam_record.status=0`。

本地一次实测结果：

- `begin_an_exam` 返回了 `ws://localhost:32769/ws`
- controller 创建了 `proctor-exam-8`
- 动态 proctor 已挂到 `stss-backend` 网络
- `student_exam_record` 查询结果为 `status=1,total_score=10`
- `student_exam_answer` 查询结果为 `question_id=20, score=10, is_correct=1`

### 6 停止动态 proctor

```bash
curl -X DELETE "$CONTROLLER/api/proctor/v1/stop/<EXAM_ID>"
```

## 6. 清理

只停服务：

```bash
docker compose down
```

连 MySQL/Redis 卷一起清理：

```bash
docker compose down -v
```

---