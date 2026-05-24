#!/bin/sh
# 向 Redis 写入测试用试卷快照，模拟 Java 下发试卷

REDIS_HOST=${REDIS_HOST:-localhost}

echo "Writing test exam paper to Redis..."

redis-cli -h "$REDIS_HOST" SET exam:1001:paper '{
  "examId": 1001,
  "title": "测试考试 - Go 基础",
  "durationMins": 60,
  "totalScore": 100,
  "questions": [
    {
      "questionId": 1,
      "type": 1,
      "score": 20,
      "stem": "Go 语言的吉祥物是什么？",
      "options": ["海豚", "地鼠", "大象", "熊猫"],
      "sortOrder": 1
    },
    {
      "questionId": 2,
      "type": 1,
      "score": 20,
      "stem": "以下哪个关键字用于声明 Go 的包名？",
      "options": ["package", "import", "module", "namespace"],
      "sortOrder": 2
    },
    {
      "questionId": 3,
      "type": 2,
      "score": 20,
      "stem": "Go 语言支持面向对象编程中的类继承。",
      "sortOrder": 3
    },
    {
      "questionId": 4,
      "type": 1,
      "score": 20,
      "stem": "Go 中用于并发通信的原语是什么？",
      "options": ["channel", "mutex", "semaphore", "pipe"],
      "sortOrder": 4
    },
    {
      "questionId": 5,
      "type": 2,
      "score": 20,
      "stem": "Go 的切片（slice）底层引用的是数组。",
      "sortOrder": 5
    }
  ]
}'

echo "Verifying data..."
redis-cli -h "$REDIS_HOST" GET exam:1001:paper | head -c 200
echo ""
echo "Test data ready."
