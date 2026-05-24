package main

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"
)

const gradingQueueKey = "grading:pending"

// SessionStatus 考试会话状态
const (
	SessionActive   = 0 // 考试中
	SessionSubmitted = 1 // 已交卷
	SessionExpired  = 2 // 超时强制交卷
)

// ExamPaper 试卷快照，与 Redis exam:{examId}:paper 对应。
type ExamPaper struct {
	ExamID       int64        `json:"examId"`
	Title        string       `json:"title"`
	DurationMins int          `json:"durationMins"`
	TotalScore   int          `json:"totalScore"`
	Questions    []QuestionVO `json:"questions"`
}

// QuestionVO 试卷中的题目。
type QuestionVO struct {
	QuestionID int64    `json:"questionId"`
	Type       int      `json:"type"`
	Score      int      `json:"score"`
	Stem       string   `json:"stem"`
	Options    []string `json:"options,omitempty"`
	SortOrder  int      `json:"sortOrder"`
}

// SessionManager 管理考试会话和答案的 Redis 操作。
type SessionManager struct {
	rdb    *redis.Client
	examID string
	paper  *ExamPaper
}

// NewSessionManager 创建会话管理器并加载试卷快照。
func NewSessionManager(rdb *redis.Client, examID string) (*SessionManager, error) {
	data, err := rdb.Get(context.Background(), paperKey(examID)).Result()
	if err != nil {
		return nil, fmt.Errorf("exam paper not found in redis: %w", err)
	}
	var paper ExamPaper
	if err := json.Unmarshal([]byte(data), &paper); err != nil {
		return nil, fmt.Errorf("failed to parse exam paper: %w", err)
	}
	return &SessionManager{rdb: rdb, examID: examID, paper: &paper}, nil
}

// Paper 返回试卷快照。
func (sm *SessionManager) Paper() *ExamPaper { return sm.paper }

// CreateSession 创建或恢复考试会话。
func (sm *SessionManager) CreateSession(ctx context.Context, studentID string, recordID int64) (map[string]string, error) {
	key := sessionKey(sm.examID, studentID)

	// 检查是否已有会话
	existing, err := sm.rdb.HGetAll(ctx, key).Result()
	if err == nil && len(existing) > 0 {
		return existing, nil
	}

	now := time.Now()
	expireTime := now.Add(time.Duration(sm.paper.DurationMins) * time.Minute)

	fields := map[string]any{
		"recordId":  recordID,
		"studentId": studentID,
		"examId":    sm.examID,
		"startTime": now.Format(time.RFC3339),
		"expireTime": expireTime.Format(time.RFC3339),
		"status":    SessionActive,
	}
	if err := sm.rdb.HSet(ctx, key, fields).Err(); err != nil {
		return nil, fmt.Errorf("failed to create session: %w", err)
	}
	// TTL = 考试时长 + 1 小时缓冲
	sm.rdb.Expire(ctx, key, time.Duration(sm.paper.DurationMins)*time.Minute+time.Hour)

	result := make(map[string]string)
	for k, v := range fields {
		result[k] = fmt.Sprint(v)
	}
	return result, nil
}

// GetSession 获取会话信息。
func (sm *SessionManager) GetSession(ctx context.Context, studentID string) (map[string]string, error) {
	return sm.rdb.HGetAll(ctx, sessionKey(sm.examID, studentID)).Result()
}

// SaveAnswer 保存单题答案。
func (sm *SessionManager) SaveAnswer(ctx context.Context, studentID string, questionID int64, answer string) error {
	key := answersKey(sm.examID, studentID)
	if err := sm.rdb.HSet(ctx, key, strconv.FormatInt(questionID, 10), answer).Err(); err != nil {
		return fmt.Errorf("failed to save answer: %w", err)
	}
	// TTL 与 session 保持一致
	sm.rdb.Expire(ctx, key, time.Duration(sm.paper.DurationMins)*time.Minute+time.Hour)
	return nil
}

// SaveAnswers 批量保存答案。
func (sm *SessionManager) SaveAnswers(ctx context.Context, studentID string, answers map[int64]string) (int, error) {
	if len(answers) == 0 {
		return 0, nil
	}
	fields := make([]any, 0, len(answers)*2)
	for qID, ans := range answers {
		fields = append(fields, strconv.FormatInt(qID, 10), ans)
	}
	key := answersKey(sm.examID, studentID)
	if err := sm.rdb.HSet(ctx, key, fields...).Err(); err != nil {
		return 0, fmt.Errorf("failed to batch save answers: %w", err)
	}
	sm.rdb.Expire(ctx, key, time.Duration(sm.paper.DurationMins)*time.Minute+time.Hour)
	return len(answers), nil
}

// GetAnswers 获取学生所有答案。
func (sm *SessionManager) GetAnswers(ctx context.Context, studentID string) (map[string]string, error) {
	return sm.rdb.HGetAll(ctx, answersKey(sm.examID, studentID)).Result()
}

// MarkSessionEnded 标记会话结束。
func (sm *SessionManager) MarkSessionEnded(ctx context.Context, studentID string, status int) error {
	return sm.rdb.HSet(ctx, sessionKey(sm.examID, studentID), "status", status).Err()
}

// RemainingSeconds 返回剩余考试时间（秒）。负数表示已超时。
func (sm *SessionManager) RemainingSeconds(ctx context.Context, studentID string) (int64, error) {
	expireStr, err := sm.rdb.HGet(ctx, sessionKey(sm.examID, studentID), "expireTime").Result()
	if err != nil {
		return 0, err
	}
	expireTime, err := time.Parse(time.RFC3339, expireStr)
	if err != nil {
		return 0, err
	}
	return int64(time.Until(expireTime).Seconds()), nil
}

// FindExpiredSessions 扫描所有会话，返回已过期但未交卷的学生 ID 列表。
func (sm *SessionManager) FindExpiredSessions(ctx context.Context) ([]string, error) {
	pattern := fmt.Sprintf("exam:%s:session:*", sm.examID)
	keys, err := sm.rdb.Keys(ctx, pattern).Result()
	if err != nil {
		return nil, err
	}

	var expired []string
	now := time.Now()
	for _, key := range keys {
		statusStr, _ := sm.rdb.HGet(ctx, key, "status").Result()
		status, _ := strconv.Atoi(statusStr)
		if status != SessionActive {
			continue
		}
		expireStr, err := sm.rdb.HGet(ctx, key, "expireTime").Result()
		if err != nil {
			continue
		}
		expireTime, err := time.Parse(time.RFC3339, expireStr)
		if err != nil {
			continue
		}
		if now.After(expireTime) {
			studentID, _ := sm.rdb.HGet(ctx, key, "studentId").Result()
			if studentID != "" {
				expired = append(expired, studentID)
			}
		}
	}
	return expired, nil
}

// Redis key helpers
func paperKey(examID string) string          { return fmt.Sprintf("exam:%s:paper", examID) }
func sessionKey(examID, studentID string) string { return fmt.Sprintf("exam:%s:session:%s", examID, studentID) }
func answersKey(examID, studentID string) string { return fmt.Sprintf("exam:%s:answers:%s", examID, studentID) }

// SubmitExam 交卷：标记会话状态并将评分任务入队。
func (sm *SessionManager) SubmitExam(ctx context.Context, studentID string) error {
	session, err := sm.GetSession(ctx, studentID)
	if err != nil || len(session) == 0 {
		return fmt.Errorf("session not found")
	}
	statusStr := session["status"]
	status, _ := strconv.Atoi(statusStr)
	if status != SessionActive {
		return fmt.Errorf("session already ended")
	}

	if err := sm.MarkSessionEnded(ctx, studentID, SessionSubmitted); err != nil {
		return err
	}
	return sm.enqueueGrading(ctx, studentID, session["recordId"])
}

// enqueueGrading 将交卷信息推入待评分队列，Java 侧消费。
func (sm *SessionManager) enqueueGrading(ctx context.Context, studentID, recordID string) error {
	payload := map[string]string{
		"examId":    sm.examID,
		"studentId": studentID,
		"recordId":  recordID,
	}
	data, _ := json.Marshal(payload)
	return sm.rdb.RPush(ctx, gradingQueueKey, string(data)).Err()
}

// CleanupSession 清理考试结束后学生的会话和答案缓存。
func (sm *SessionManager) CleanupSession(ctx context.Context, studentID string) {
	sm.rdb.Del(ctx, sessionKey(sm.examID, studentID), answersKey(sm.examID, studentID))
}
