package main

import (
	"context"
	"fmt"
	"log"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"

	pb "ot-go/pkg/proctor/v1"
)

const gradingQueueKey = "grading:pending"

// SessionStatus 考试会话状态
const (
	SessionActive    = 0 // 考试中
	SessionSubmitted = 1 // 已交卷
	SessionExpired   = 2 // 超时强制交卷
)

// SessionManager 管理考试会话和答案的 Redis 操作。
type SessionManager struct {
	rdb       *redis.Client
	examID    string
	paper     *pb.ExamPaper
	javaAddr  string           // Java gRPC 地址，为空时仅用 Redis 队列
	grpcConn  *grpc.ClientConn
	grpcCli   pb.ProctorServiceClient
}

// NewSessionManager 创建会话管理器并加载试卷快照。
// 优先尝试 proto 二进制格式，失败则回退到 JSON（兼容测试脚本）。
func NewSessionManager(rdb *redis.Client, examID, javaAddr string) (*SessionManager, error) {
	data, err := rdb.Get(context.Background(), paperKey(examID)).Result()
	if err != nil {
		return nil, fmt.Errorf("exam paper not found in redis: %w", err)
	}

	var paper pb.ExamPaper
	raw := []byte(data)

	if err := proto.Unmarshal(raw, &paper); err != nil {
		// 回退 JSON（测试/开发环境使用）
		if err := protojson.Unmarshal(raw, &paper); err != nil {
			return nil, fmt.Errorf("failed to parse exam paper (proto+json): %w", err)
		}
	}

	sm := &SessionManager{
		rdb:      rdb,
		examID:   examID,
		paper:    &paper,
		javaAddr: javaAddr,
	}

	// 尝试连接 Java gRPC
	if javaAddr != "" {
		conn, err := grpc.NewClient(javaAddr, grpc.WithTransportCredentials(insecure.NewCredentials()))
		if err != nil {
			log.Printf("gRPC: Java unreachable at %s, falling back to Redis queue: %v", javaAddr, err)
		} else {
			sm.grpcConn = conn
			sm.grpcCli = pb.NewProctorServiceClient(conn)
			log.Printf("gRPC: connected to Java at %s", javaAddr)
		}
	}

	return sm, nil
}

// Close 关闭 gRPC 连接。
func (sm *SessionManager) Close() {
	if sm.grpcConn != nil {
		sm.grpcConn.Close()
	}
}

// Paper 返回试卷快照。
func (sm *SessionManager) Paper() *pb.ExamPaper { return sm.paper }

// PaperJSON 返回试卷的 JSON 视图（用于 HTTP API 响应）。
func (sm *SessionManager) PaperJSON() map[string]any {
	qList := make([]map[string]any, 0, len(sm.paper.Questions))
	for _, q := range sm.paper.Questions {
		qList = append(qList, map[string]any{
			"questionId": q.QuestionId,
			"type":       q.Type,
			"score":      q.Score,
			"stem":       q.Stem,
			"options":    q.Options,
			"sortOrder":  q.SortOrder,
		})
	}
	return map[string]any{
		"examId":       sm.paper.ExamId,
		"title":        sm.paper.Title,
		"durationMins": sm.paper.DurationMins,
		"totalScore":   sm.paper.TotalScore,
		"questions":    qList,
	}
}

// CreateSession 创建或恢复考试会话。
func (sm *SessionManager) CreateSession(ctx context.Context, studentID string, recordID int64) (map[string]string, error) {
	key := sessionKey(sm.examID, studentID)

	existing, err := sm.rdb.HGetAll(ctx, key).Result()
	if err == nil && len(existing) > 0 {
		return existing, nil
	}

	now := time.Now()
	expireTime := now.Add(time.Duration(sm.paper.DurationMins) * time.Minute)

	fields := map[string]any{
		"recordId":   recordID,
		"studentId":  studentID,
		"examId":     sm.examID,
		"startTime":  now.Format(time.RFC3339),
		"expireTime": expireTime.Format(time.RFC3339),
		"status":     SessionActive,
	}
	if err := sm.rdb.HSet(ctx, key, fields).Err(); err != nil {
		return nil, fmt.Errorf("failed to create session: %w", err)
	}
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

// SubmitExam 交卷：收集答案，通过 gRPC 调 Java 评分，gRPC 不可用时退到 Redis 队列。
func (sm *SessionManager) SubmitExam(ctx context.Context, studentID string) error {
	session, err := sm.GetSession(ctx, studentID)
	if err != nil || len(session) == 0 {
		return fmt.Errorf("session not found")
	}
	status, _ := strconv.Atoi(session["status"])
	if status != SessionActive {
		return fmt.Errorf("session already ended")
	}

	recordID, _ := strconv.ParseInt(session["recordId"], 10, 64)

	if err := sm.MarkSessionEnded(ctx, studentID, SessionSubmitted); err != nil {
		return err
	}

	// 收集答案
	answers, _ := sm.GetAnswers(ctx, studentID)
	entries := make([]*pb.AnswerEntry, 0, len(answers))
	for qIDStr, ans := range answers {
		qID, _ := strconv.ParseInt(qIDStr, 10, 64)
		entries = append(entries, &pb.AnswerEntry{
			QuestionId:    qID,
			StudentAnswer: ans,
		})
	}

	req := &pb.CommitExamRequest{
		ExamId:     sm.paper.ExamId,
		StudentId:  parseStudentID(studentID),
		RecordId:   recordID,
		Answers:    entries,
		SubmitType: 0, // 主动交卷
		SubmitTime: time.Now().Unix(),
	}

	// 尝试 gRPC 直接调 Java
	if sm.grpcCli != nil {
		resp, err := sm.grpcCli.CommitExam(ctx, req)
		if err == nil && resp.Success {
			log.Printf("gRPC: commit exam succeeded for student %s, score=%d", studentID, resp.TotalScore)
			return nil
		}
		log.Printf("gRPC: commit exam failed for student %s: %v, falling back to Redis queue", studentID, err)
	}

	// 降级：写入 Redis 队列
	return sm.enqueueGrading(ctx, studentID, strconv.FormatInt(recordID, 10))
}

// enqueueGrading 将评分任务推入 Redis 队列。
func (sm *SessionManager) enqueueGrading(ctx context.Context, studentID, recordID string) error {
	task := &pb.GradingTask{
		ExamId:     sm.paper.ExamId,
		StudentId:  parseStudentID(studentID),
		RecordId:   parseRecordID(recordID),
		SubmitTime: time.Now().Unix(),
	}
	data, err := proto.Marshal(task)
	if err != nil {
		return fmt.Errorf("failed to marshal grading task: %w", err)
	}
	return sm.rdb.RPush(ctx, gradingQueueKey, string(data)).Err()
}

// CleanupSession 清理考试结束后学生的会话和答案缓存。
func (sm *SessionManager) CleanupSession(ctx context.Context, studentID string) {
	sm.rdb.Del(ctx, sessionKey(sm.examID, studentID), answersKey(sm.examID, studentID))
}

// Redis key helpers
func paperKey(examID string) string             { return fmt.Sprintf("exam:%s:paper", examID) }
func sessionKey(examID, studentID string) string  { return fmt.Sprintf("exam:%s:session:%s", examID, studentID) }
func answersKey(examID, studentID string) string  { return fmt.Sprintf("exam:%s:answers:%s", examID, studentID) }

func parseStudentID(s string) int64 {
	id, _ := strconv.ParseInt(s, 10, 64)
	return id
}

func parseRecordID(s string) int64 {
	id, _ := strconv.ParseInt(s, 10, 64)
	return id
}
