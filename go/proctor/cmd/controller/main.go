package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"

	pb "logger/pkg/loggerServer/v1"
	"ot-go/pkg/loggerclient"
)

func main() {
	// ---- 环境变量 ----
	loggerAddr := os.Getenv("LOGGER_ADDR")
	redisAddr := os.Getenv("REDIS_ADDR")
	if redisAddr == "" {
		redisAddr = "localhost:6379"
	}
	proctorImage := os.Getenv("PROCTOR_IMAGE")
	if proctorImage == "" {
		proctorImage = "ot-proctor:latest"
	}
	javaAddr := os.Getenv("JAVA_ADDR")
	proctorNetwork := os.Getenv("PROCTOR_NETWORK")
	publicHost := os.Getenv("PROCTOR_PUBLIC_HOST")
	if publicHost == "" {
		publicHost = "localhost"
	}
	wsScheme := os.Getenv("PROCTOR_WS_SCHEME")
	if wsScheme == "" {
		wsScheme = "ws"
	}
	controllerPort := os.Getenv("CONTROLLER_PORT")
	if controllerPort == "" {
		controllerPort = "8080"
	}
	cleanupTTL := 2 * time.Hour // 容器过期清理时间

	// ---- Logger ----
	logClient, err := loggerclient.New(loggerAddr, "proctor-controller-service")
	if err != nil {
		log.Printf("logger unavailable: %v", err)
	} else {
		defer logClient.Close()
	}

	// ---- Redis ----
	redisClient := redis.NewClient(&redis.Options{Addr: redisAddr})
	if err := redisClient.Ping(context.Background()).Err(); err != nil {
		log.Printf("redis unavailable: %v", err)
	} else {
		defer redisClient.Close()
	}

	// ---- Docker 注册表 ----
	registry, err := NewProctorRegistry(proctorImage, redisAddr, loggerAddr, javaAddr, proctorNetwork)
	if err != nil {
		log.Fatalf("failed to init docker registry: %v", err)
	}
	defer registry.Close()

	// ---- 后台清理 ----
	go func() {
		ticker := time.NewTicker(10 * time.Minute)
		defer ticker.Stop()
		for range ticker.C {
			cleaned := registry.CleanupExpired(cleanupTTL)
			for _, examID := range cleaned {
				log.Printf("cleaned expired proctor for exam %s", examID)
				_ = redisClient.Del(context.Background(), proctorKey(examID))
			}
		}
	}()

	// ---- HTTP 路由 ----
	r := gin.Default()
	r.Use(requestLogger(logClient))

	r.POST("/api/proctor/v1/start", startHandler(registry, redisClient, logClient, cleanupTTL, publicHost, wsScheme))
	r.GET("/api/proctor/v1/status/:examId", statusHandler(registry, logClient))
	r.DELETE("/api/proctor/v1/stop/:examId", stopHandler(registry, redisClient, logClient))

	// 兼容旧接口
	r.POST("/api/exam/v1/start-proctor", startHandler(registry, redisClient, logClient, cleanupTTL, publicHost, wsScheme))

	// ---- 优雅退出 ----
	srv := &http.Server{Addr: ":" + controllerPort, Handler: r}
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("shutting down...")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	srv.Shutdown(ctx)
}

// ---- 路由处理器 ----

func startHandler(
	reg *ProctorRegistry,
	rdb *redis.Client,
	lc *loggerclient.Client,
	cleanupTTL time.Duration,
	publicHost string,
	wsScheme string,
) gin.HandlerFunc {
	return func(c *gin.Context) {
		examID := c.Query("examId")
		if examID == "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": "examId is required"})
			return
		}

		// 校验 Redis 中是否存在试卷快照
		paperData, err := rdb.Get(c.Request.Context(), paperKey(examID)).Result()
		if err != nil {
			_ = logError(lc, c, "exam paper not found in redis", examID, err)
			c.JSON(http.StatusNotFound, gin.H{"error": "exam paper not ready, please start the exam first"})
			return
		}

		// 从试卷快照中获取过期时间
		var paper struct {
			ValidEndTime int64 `json:"validEndTime"`
		}
		if err := json.Unmarshal([]byte(paperData), &paper); err == nil && paper.ValidEndTime > 0 {
			if time.Now().Unix() > paper.ValidEndTime {
				c.JSON(http.StatusGone, gin.H{"error": "exam has expired"})
				return
			}
		}

		// 获取或创建容器
		inst, err := reg.GetOrCreate(examID)
		if err != nil {
			_ = logError(lc, c, "start proctor container failed", examID, err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		// 写入 Redis 供前端发现
		wsEndpoint := fmt.Sprintf("%s://%s:%s/ws", wsScheme, publicHost, inst.HostPort)
		proctorInfo := map[string]string{
			"examId":      examID,
			"hostPort":    inst.HostPort,
			"wsEndpoint":  wsEndpoint,
			"containerId": inst.ContainerID,
		}
		rdb.Set(c.Request.Context(), proctorKey(examID), mustMarshal(proctorInfo), cleanupTTL)

		_ = logInfo(lc, c, "proctor container started", examID)
		c.JSON(http.StatusOK, gin.H{
			"message":     "proctor started",
			"examId":      examID,
			"ws_endpoint": wsEndpoint,
		})
	}
}

func statusHandler(reg *ProctorRegistry, lc *loggerclient.Client) gin.HandlerFunc {
	return func(c *gin.Context) {
		examID := c.Param("examId")
		inst, err := reg.GetOrCreate(examID)
		if err != nil {
			c.JSON(http.StatusNotFound, gin.H{"error": "proctor not found"})
			return
		}
		c.JSON(http.StatusOK, gin.H{
			"examId":      examID,
			"wsEndpoint":  fmt.Sprintf("ws://localhost:%s/ws", inst.HostPort),
			"containerId": inst.ContainerID,
			"createdAt":   inst.CreatedAt,
		})
	}
}

func stopHandler(reg *ProctorRegistry, rdb *redis.Client, lc *loggerclient.Client) gin.HandlerFunc {
	return func(c *gin.Context) {
		examID := c.Param("examId")
		if err := reg.Remove(examID); err != nil {
			_ = logError(lc, c, "stop proctor container failed", examID, err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		rdb.Del(c.Request.Context(), proctorKey(examID))
		_ = logInfo(lc, c, "proctor container stopped", examID)
		c.JSON(http.StatusOK, gin.H{"message": "proctor stopped", "examId": examID})
	}
}

// ---- Redis Key 工具 ----

func paperKey(examID string) string   { return fmt.Sprintf("exam:%s:paper", examID) }
func proctorKey(examID string) string { return fmt.Sprintf("exam:%s:proctor", examID) }

// ---- 中间件 ----

func requestLogger(lc *loggerclient.Client) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		_ = lc.Log(c.Request.Context(), pb.LogLevel_LOG_LEVEL_INFO, "http request", loggerclient.Fields{
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

func logInfo(lc *loggerclient.Client, c *gin.Context, msg, examID string) error {
	return lc.Log(c.Request.Context(), pb.LogLevel_LOG_LEVEL_INFO, msg, loggerclient.Fields{
		TraceID:    c.Request.Header.Get("X-Trace-Id"),
		UserID:     c.Request.Header.Get("X-User-Id"),
		EntityType: "exam",
		EntityID:   examID,
	})
}

func logError(lc *loggerclient.Client, c *gin.Context, msg, examID string, err error) error {
	return lc.Log(c.Request.Context(), pb.LogLevel_LOG_LEVEL_ERROR, msg, loggerclient.Fields{
		TraceID:      c.Request.Header.Get("X-Trace-Id"),
		UserID:       c.Request.Header.Get("X-User-Id"),
		EntityType:   "exam",
		EntityID:     examID,
		ErrorMessage: err.Error(),
	})
}

func mustMarshal(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}
