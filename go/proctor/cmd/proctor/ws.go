package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = 30 * time.Second
	maxMessageSize = 4096
)

// WSMessage WebSocket 消息协议。
type WSMessage struct {
	Type string `json:"type"`
	// ping / pong
	ServerTime int64 `json:"serverTime,omitempty"`
	// save_answer / ack
	QuestionID int64  `json:"questionId,omitempty"`
	Answer     string `json:"answer,omitempty"`
	Status     string `json:"status,omitempty"`
	// status sync
	RemainingSeconds int64 `json:"remainingSeconds,omitempty"`
	AnsweredCount    int   `json:"answeredCount,omitempty"`
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin:     func(r *http.Request) bool { return true },
}

// handleWS 处理单个学生的 WebSocket 连接。
func handleWS(studentID string, recordID int64, sm *SessionManager, conns *sync.Map, conn *websocket.Conn) {
	conns.Store(studentID, conn)
	defer func() {
		conns.Delete(studentID)
		conn.Close()
	}()

	ctx := context.Background()

	// 创建或恢复会话
	session, err := sm.CreateSession(ctx, studentID, recordID)
	if err != nil {
		log.Printf("failed to create session for student %s: %v", studentID, err)
		return
	}

	answers, _ := sm.GetAnswers(ctx, studentID)
	remaining, _ := sm.RemainingSeconds(ctx, studentID)
	log.Printf("student %s connected, remaining %ds, %d saved answers", studentID, remaining, len(answers))

	// 初始状态同步
	if remaining > 0 {
		sendStatus(conn, remaining, len(answers))
	}

	// 写入协程
	done := make(chan struct{})
	go wsWriter(conn, sm, studentID, done)

	// 读取循环
	conn.SetReadLimit(maxMessageSize)
	conn.SetReadDeadline(time.Now().Add(pongWait))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseNormalClosure) {
				log.Printf("ws read error for student %s: %v", studentID, err)
			}
			break
		}

		var msg WSMessage
		if err := json.Unmarshal(msgBytes, &msg); err != nil {
			writeJSON(conn, WSMessage{Type: "error", Status: "invalid message format"})
			continue
		}

		switch msg.Type {
		case "ping":
			writeJSON(conn, WSMessage{Type: "pong", ServerTime: time.Now().Unix()})

		case "save_answer":
			if err := sm.SaveAnswer(ctx, studentID, msg.QuestionID, msg.Answer); err != nil {
				log.Printf("failed to save answer for student %s: %v", studentID, err)
				writeJSON(conn, WSMessage{Type: "ack", QuestionID: msg.QuestionID, Status: "error"})
				continue
			}
			answers, _ := sm.GetAnswers(ctx, studentID)
			remaining, _ := sm.RemainingSeconds(ctx, studentID)
			writeJSON(conn, WSMessage{Type: "ack", QuestionID: msg.QuestionID, Status: "saved"})
			sendStatus(conn, remaining, len(answers))

		case "submit":
			if err := sm.SubmitExam(ctx, studentID); err != nil {
				log.Printf("submit failed for student %s: %v", studentID, err)
				writeJSON(conn, WSMessage{Type: "submitted", Status: "error: " + err.Error()})
				continue
			}
			log.Printf("student %s submitted exam", studentID)
			writeJSON(conn, WSMessage{Type: "submitted", Status: "ok"})
			conns.Delete(studentID)

		default:
			writeJSON(conn, WSMessage{Type: "error", Status: "unknown message type: " + msg.Type})
		}
	}

	close(done)
	_ = session
}

// wsWriter 写入协程，负责心跳和状态推送。
func wsWriter(conn *websocket.Conn, sm *SessionManager, studentID string, done <-chan struct{}) {
	pingTicker := time.NewTicker(pingPeriod)
	defer pingTicker.Stop()

	for {
		select {
		case <-done:
			return
		case <-pingTicker.C:
			conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := writeJSON(conn, WSMessage{
				Type:       "ping",
				ServerTime: time.Now().Unix(),
			}); err != nil {
				log.Printf("ws ping failed for student %s: %v", studentID, err)
				return
			}
		}
	}
}

// sendStatus 推送考试状态给客户端。
func sendStatus(conn *websocket.Conn, remainingSeconds int64, answeredCount int) {
	writeJSON(conn, WSMessage{
		Type:             "status",
		RemainingSeconds: remainingSeconds,
		AnsweredCount:    answeredCount,
	})
}

// writeJSON 写入 JSON 消息。
func writeJSON(conn *websocket.Conn, msg WSMessage) error {
	conn.SetWriteDeadline(time.Now().Add(writeWait))
	return conn.WriteJSON(msg)
}

// NotifyExpired 通知学生考试已超时强制交卷。
func NotifyExpired(conn *websocket.Conn) {
	writeJSON(conn, WSMessage{
		Type:   "exam_expired",
		Status: "time is up, exam submitted",
	})
}
