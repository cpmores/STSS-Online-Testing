package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
	"github.com/redis/go-redis/v9"

	pb "logger/pkg/loggerServer/v1"
	"ot-go/pkg/loggerclient"
)

func main() {
	examID := os.Getenv("EXAM_ID")
	if examID == "" {
		log.Fatal("EXAM_ID is required")
	}
	loggerAddr := os.Getenv("LOGGER_ADDR")
	redisAddr := os.Getenv("REDIS_ADDR")
	if redisAddr == "" {
		redisAddr = "localhost:6379"
	}
	javaAddr := os.Getenv("JAVA_ADDR") // Java gRPC 地址，为空时仅用 Redis 队列

	// ---- Logger ----
	logClient, err := loggerclient.New(loggerAddr, "proctor-service")
	if err != nil {
		log.Printf("logger unavailable: %v", err)
	} else {
		defer logClient.Close()
	}

	// ---- Redis ----
	rdb := redis.NewClient(&redis.Options{Addr: redisAddr})
	if err := rdb.Ping(context.Background()).Err(); err != nil {
		log.Fatalf("redis unavailable: %v", err)
	}
	defer rdb.Close()

	// ---- Session Manager ----
	sm, err := NewSessionManager(rdb, examID, javaAddr)
	if err != nil {
		log.Fatalf("failed to load exam paper: %v", err)
	}

	// ---- 活跃连接追踪 ----
	activeConns := &sync.Map{} // studentID → *websocket.Conn

	// ---- 后台：强制交卷检测 ----
	go expiryWatcher(sm, activeConns, logClient)

	// ---- HTTP 路由 ----
	r := gin.Default()
	r.Use(requestLogger(logClient))

	r.GET("/ws", func(c *gin.Context) {
		studentID := c.Query("studentId")
		recordIDStr := c.Query("recordId")
		if studentID == "" || recordIDStr == "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": "studentId and recordId are required"})
			return
		}
		recordID, err := strconv.ParseInt(recordIDStr, 10, 64)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid recordId"})
			return
		}

		conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
		if err != nil {
			return
		}
		handleWS(studentID, recordID, sm, activeConns, conn)
	})

	r.POST("/api/proctor/v1/answers/save", saveAnswerHandler(sm, logClient))
	r.POST("/api/proctor/v1/answers/batch", batchSaveHandler(sm, logClient))
	r.POST("/api/proctor/v1/answers/submit", submitHandler(sm, logClient))
	r.GET("/api/proctor/v1/session/:studentId", sessionHandler(sm))

	// ---- 启动 ----
	srv := &http.Server{Addr: ":9090", Handler: r}
	go func() {
		log.Printf("proctor for exam %s started on :9090", examID)
		_ = logClient.Log(context.Background(), pb.LogLevel_LOG_LEVEL_INFO, "proctor started", loggerclient.Fields{
			EntityType: "exam",
			EntityID:   examID,
		})
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	// ---- 优雅退出 ----
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("shutting down...")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	srv.Shutdown(ctx)
}

// ---- HTTP Handlers ----

func saveAnswerHandler(sm *SessionManager, lc *loggerclient.Client) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			StudentID  string `json:"studentId" binding:"required"`
			ExamID     string `json:"examId" binding:"required"`
			QuestionID int64  `json:"questionId" binding:"required"`
			Answer     string `json:"answer" binding:"required"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
			return
		}
		// 校验会话是否存在（应由先前的 WS 连接创建）
		session, err := sm.GetSession(c.Request.Context(), req.StudentID)
		if err != nil || len(session) == 0 {
			c.JSON(http.StatusNotFound, gin.H{"error": "session not found, please reconnect via WebSocket first"})
			return
		}
		if err := sm.SaveAnswer(c.Request.Context(), req.StudentID, req.QuestionID, req.Answer); err != nil {
			_ = logError(lc, c, "save answer failed", req.StudentID, err)
			if isValidationError(err) {
				c.JSON(http.StatusUnprocessableEntity, gin.H{"error": err.Error()})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		answers, _ := sm.GetAnswers(c.Request.Context(), req.StudentID)
		_ = logInfo(lc, c, "answer saved", req.StudentID, loggerclient.Fields{
			IntFields: map[string]int64{"answers_total": int64(len(answers))},
		})
		c.JSON(http.StatusOK, gin.H{"message": "saved"})
	}
}

func batchSaveHandler(sm *SessionManager, lc *loggerclient.Client) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			StudentID string `json:"studentId" binding:"required"`
			ExamID    string `json:"examId" binding:"required"`
			Answers   []struct {
				QuestionID int64  `json:"questionId"`
				Answer     string `json:"answer"`
			} `json:"answers" binding:"required"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
			return
		}
		// 校验会话是否存在
		session, err := sm.GetSession(c.Request.Context(), req.StudentID)
		if err != nil || len(session) == 0 {
			c.JSON(http.StatusNotFound, gin.H{"error": "session not found, please reconnect via WebSocket first"})
			return
		}
		batch := make(map[int64]string, len(req.Answers))
		for _, a := range req.Answers {
			batch[a.QuestionID] = a.Answer
		}
		count, err := sm.SaveAnswers(c.Request.Context(), req.StudentID, batch)
		if err != nil {
			_ = logError(lc, c, "batch save failed", req.StudentID, err)
			if isValidationError(err) {
				c.JSON(http.StatusUnprocessableEntity, gin.H{"error": err.Error()})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		_ = logInfo(lc, c, "answers batch saved", req.StudentID, loggerclient.Fields{
			IntFields: map[string]int64{"saved_count": int64(count)},
		})
		c.JSON(http.StatusOK, gin.H{"message": "batch saved", "savedCount": count})
	}
}

func submitHandler(sm *SessionManager, lc *loggerclient.Client) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			StudentID string `json:"studentId" binding:"required"`
			ExamID    string `json:"examId" binding:"required"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid payload"})
			return
		}
		if err := sm.SubmitExam(c.Request.Context(), req.StudentID, 0); err != nil {
			_ = logError(lc, c, "submit exam failed", req.StudentID, err)
			if isValidationError(err) {
				c.JSON(http.StatusUnprocessableEntity, gin.H{"error": err.Error()})
				return
			}
			c.JSON(http.StatusConflict, gin.H{"error": err.Error()})
			return
		}
		_ = logInfo(lc, c, "exam submitted", req.StudentID, loggerclient.Fields{})
		c.JSON(http.StatusOK, gin.H{"message": "exam submitted"})
	}
}

func sessionHandler(sm *SessionManager) gin.HandlerFunc {
	return func(c *gin.Context) {
		studentID := c.Param("studentId")
		session, err := sm.GetSession(c.Request.Context(), studentID)
		if err != nil || len(session) == 0 {
			c.JSON(http.StatusNotFound, gin.H{"error": "session not found"})
			return
		}
		answers, _ := sm.GetAnswers(c.Request.Context(), studentID)
		remaining, _ := sm.RemainingSeconds(c.Request.Context(), studentID)
		paper := sm.PaperJSON()
		c.JSON(http.StatusOK, gin.H{
			"examId":           paper["examId"],
			"title":            paper["title"],
			"remainingSeconds": remaining,
			"answers":          answers,
			"questions":        paper["questions"],
			"session":          session,
		})
	}
}

// ---- 后台：过期会话检测 ----

func expiryWatcher(sm *SessionManager, conns *sync.Map, lc *loggerclient.Client) {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		ctx := context.Background()
		expired, err := sm.FindExpiredSessions(ctx)
		if err != nil {
			log.Printf("expiry scan error: %v", err)
			continue
		}
		for _, studentID := range expired {
			if err := sm.SubmitExam(ctx, studentID, SessionExpired); err != nil {
				log.Printf("forced submit failed for student %s: %v", studentID, err)
				continue
			}
			if conn, ok := conns.LoadAndDelete(studentID); ok {
				wsConn := conn.(*websocket.Conn)
				NotifyExpired(wsConn)
				wsConn.Close()
			}
			log.Printf("student %s exam expired, forced submit", studentID)
			_ = lc.Log(ctx, pb.LogLevel_LOG_LEVEL_WARN, "exam expired, forced submit", loggerclient.Fields{
				UserID:     studentID,
				EntityType: "exam",
				EntityID:   sm.examID,
			})
		}
	}
}

// ---- 中间件 ----

func requestLogger(lc *loggerclient.Client) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		_ = lc.Log(context.Background(), pb.LogLevel_LOG_LEVEL_INFO, "http request", loggerclient.Fields{
			TraceID:    c.Request.Header.Get("X-Trace-Id"),
			UserID:     c.Request.Header.Get("X-User-Id"),
			Method:     c.Request.Method,
			Path:       c.Request.URL.Path,
			StatusCode: int32(c.Writer.Status()),
			DurationMS: time.Since(start).Milliseconds(),
		})
	}
}

// ---- 工具函数 ----

func logInfo(lc *loggerclient.Client, c *gin.Context, msg, studentID string, extra loggerclient.Fields) error {
	extra.TraceID = c.Request.Header.Get("X-Trace-Id")
	extra.UserID = studentID
	extra.EntityType = "exam"
	return lc.Log(context.Background(), pb.LogLevel_LOG_LEVEL_INFO, msg, extra)
}

func logError(lc *loggerclient.Client, c *gin.Context, msg, studentID string, err error) error {
	return lc.Log(context.Background(), pb.LogLevel_LOG_LEVEL_ERROR, msg, loggerclient.Fields{
		TraceID:      c.Request.Header.Get("X-Trace-Id"),
		UserID:       studentID,
		EntityType:   "exam",
		ErrorMessage: err.Error(),
	})
}
