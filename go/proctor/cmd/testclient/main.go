package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/gorilla/websocket"
)

const (
	baseURL = "http://localhost:9090"
	wsURL   = "ws://localhost:9090/ws"
)

var (
	passed int
	failed int
)

func main() {
	fmt.Println("========================================")
	fmt.Println("  Proctor 集成测试")
	fmt.Println("========================================")
	fmt.Println()

	// 先创建会话（HTTP 测试依赖 session 存在）
	fmt.Println("--- 准备：创建测试会话 ---")
	createSession("u10001")

	// ---- HTTP 测试 ----
	fmt.Println()
	fmt.Println("--- HTTP API 测试 ---")

	testSaveAnswer()
	testBatchSave()
	testGetSession()

	// ---- WebSocket 测试 ----
	fmt.Println()
	fmt.Println("--- WebSocket 测试 ---")

	testWSConnect()
	testWSPingPong()
	testWSSaveAnswer()
	testWSSubmit()

	// ---- HTTP 交卷测试 ----
	fmt.Println()
	fmt.Println("--- HTTP 交卷测试 ---")
	testHTTPSubmit()

	// ---- 结果 ----
	fmt.Println()
	fmt.Println("========================================")
	fmt.Printf("  结果: %d 通过, %d 失败\n", passed, failed)
	fmt.Println("========================================")

	if failed > 0 {
		os.Exit(1)
	}
}

func createSession(studentID string) {
	url := fmt.Sprintf("%s?studentId=%s&recordId=888", wsURL, studentID)
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		fmt.Printf("  WARN  创建会话失败: %v\n", err)
		return
	}
	// 读取初始状态后断开
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, msgBytes, err := conn.ReadMessage()
	conn.Close()
	if err != nil {
		fmt.Printf("  WARN  读取初始状态失败: %v\n", err)
		return
	}
	var msg map[string]any
	json.Unmarshal(msgBytes, &msg)
	if msg["type"] == "status" {
		fmt.Printf("  会话已创建 (studentId=%s)\n", studentID)
	}
}

func ok(name string) {
	passed++
	fmt.Printf("  PASS  %s\n", name)
}

func okf(format string, args ...any) {
	passed++
	fmt.Printf("  PASS  "+format+"\n", args...)
}

func fail(name string, msg string, args ...any) {
	failed++
	fmt.Printf("  FAIL  %s: %s\n", name, fmt.Sprintf(msg, args...))
}

func httpPost(url string, body any) (*http.Response, []byte) {
	b, _ := json.Marshal(body)
	resp, err := http.Post(url, "application/json", bytes.NewReader(b))
	if err != nil {
		return nil, nil
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return resp, data
}

func httpGet(url string) (*http.Response, []byte) {
	resp, err := http.Get(url)
	if err != nil {
		return nil, nil
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return resp, data
}

// ---- HTTP Tests ----

func testSaveAnswer() {
	payload := map[string]any{
		"studentId":  "u10001",
		"examId":     "1001",
		"questionId": 1,
		"answer":     "地鼠",
	}
	resp, data := httpPost(baseURL+"/api/proctor/v1/answers/save", payload)

	if resp == nil {
		fail("POST /answers/save", "连接失败")
		return
	}
	if resp.StatusCode != 200 {
		fail("POST /answers/save", "状态码 %d, body: %s", resp.StatusCode, string(data))
		return
	}

	var result map[string]any
	json.Unmarshal(data, &result)
	if result["message"] != "saved" {
		fail("POST /answers/save", "响应不正确: %s", string(data))
		return
	}
	ok("POST /answers/save")
}

func testBatchSave() {
	payload := map[string]any{
		"studentId": "u10001",
		"examId":    "1001",
		"answers": []map[string]any{
			{"questionId": 2, "answer": "package"},
			{"questionId": 3, "answer": "False"},
			{"questionId": 4, "answer": "channel"},
		},
	}
	resp, data := httpPost(baseURL+"/api/proctor/v1/answers/batch", payload)

	if resp == nil {
		fail("POST /answers/batch", "连接失败")
		return
	}
	if resp.StatusCode != 200 {
		fail("POST /answers/batch", "状态码 %d, body: %s", resp.StatusCode, string(data))
		return
	}

	var result map[string]any
	json.Unmarshal(data, &result)
	count, _ := result["savedCount"].(float64)
	if int(count) != 3 {
		fail("POST /answers/batch", "期望保存 3 条, 实际 %.0f", count)
		return
	}
	ok("POST /answers/batch")
}

func testGetSession() {
	resp, data := httpGet(baseURL + "/api/proctor/v1/session/u10001")

	if resp == nil {
		fail("GET /session/:id", "连接失败")
		return
	}
	if resp.StatusCode != 200 {
		fail("GET /session/:id", "状态码 %d, body: %s", resp.StatusCode, string(data))
		return
	}

	var result map[string]any
	json.Unmarshal(data, &result)

	answers, ok := result["answers"].(map[string]any)
	if !ok {
		fail("GET /session/:id", "answers 字段缺失")
		return
	}
	if len(answers) < 4 {
		fail("GET /session/:id", "期望 4 条答案, 实际 %d", len(answers))
		return
	}
	title, _ := result["title"].(string)
	if title == "" {
		fail("GET /session/:id", "title 字段缺失")
		return
	}
	okf("GET /session/:id  (已恢复 %d 条答案, 试卷: %s)", len(answers), title)
}

// ---- WebSocket Tests ----

func dialWS() *websocket.Conn {
	url := fmt.Sprintf("%s?studentId=u10002&recordId=999", wsURL)
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		return nil
	}
	return conn
}

func testWSConnect() {
	conn := dialWS()
	if conn == nil {
		fail("WS 连接", "连接失败")
		return
	}
	defer conn.Close()

	// 读取初始状态推送
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, msgBytes, err := conn.ReadMessage()
	if err != nil {
		fail("WS 连接", "未收到初始状态推送: %v", err)
		return
	}

	var msg map[string]any
	json.Unmarshal(msgBytes, &msg)
	if msg["type"] != "status" {
		fail("WS 连接", "首条消息不是 status: %s", string(msgBytes))
		return
	}

	remaining, _ := msg["remainingSeconds"].(float64)
	if remaining <= 0 {
		fail("WS 连接", "remainingSeconds 无效: %.0f", remaining)
		return
	}
	okf("WS 连接 + 初始状态 (剩余 %.0f 秒)", remaining)
}

func testWSPingPong() {
	conn := dialWS()
	if conn == nil {
		fail("WS ping/pong", "连接失败")
		return
	}
	defer conn.Close()

	// 跳过初始 status 消息
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	conn.ReadMessage()

	// 发送 ping
	pingMsg := `{"type":"ping"}`
	if err := conn.WriteMessage(websocket.TextMessage, []byte(pingMsg)); err != nil {
		fail("WS ping/pong", "发送 ping 失败: %v", err)
		return
	}

	// 接收 pong
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, msgBytes, err := conn.ReadMessage()
	if err != nil {
		fail("WS ping/pong", "未收到 pong: %v", err)
		return
	}

	var msg map[string]any
	json.Unmarshal(msgBytes, &msg)
	if msg["type"] != "pong" {
		fail("WS ping/pong", "响应不是 pong: %s", string(msgBytes))
		return
	}
	ok("WS ping/pong")
}

func testWSSaveAnswer() {
	conn := dialWS()
	if conn == nil {
		fail("WS save_answer", "连接失败")
		return
	}
	defer conn.Close()

	// 跳过初始状态消息
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	conn.ReadMessage()

	// 发送答案
	saveMsg := map[string]any{
		"type":       "save_answer",
		"questionId": 1,
		"answer":     "地鼠",
	}
	b, _ := json.Marshal(saveMsg)
	if err := conn.WriteMessage(websocket.TextMessage, b); err != nil {
		fail("WS save_answer", "发送失败: %v", err)
		return
	}

	// 应收到 ack
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, ackBytes, err := conn.ReadMessage()
	if err != nil {
		fail("WS save_answer", "未收到 ack: %v", err)
		return
	}

	var ack map[string]any
	json.Unmarshal(ackBytes, &ack)
	if ack["type"] != "ack" || ack["status"] != "saved" {
		fail("WS save_answer", "ack 不正确: %s", string(ackBytes))
		return
	}

	// 跳过可能的状态推送
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	conn.ReadMessage()

	ok("WS save_answer → ack")
}

func testWSSubmit() {
	conn := dialWS()
	if conn == nil {
		fail("WS submit", "连接失败")
		return
	}
	defer conn.Close()

	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	conn.ReadMessage()

	saveMsg := map[string]any{"type": "submit"}
	b, _ := json.Marshal(saveMsg)
	if err := conn.WriteMessage(websocket.TextMessage, b); err != nil {
		fail("WS submit", "发送失败: %v", err)
		return
	}

	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, ackBytes, err := conn.ReadMessage()
	if err != nil {
		fail("WS submit", "未收到响应: %v", err)
		return
	}

	var ack map[string]any
	json.Unmarshal(ackBytes, &ack)
	if ack["type"] != "submitted" || ack["status"] != "ok" {
		fail("WS submit", "响应不正确: %s", string(ackBytes))
		return
	}
	ok("WS submit → submitted ok")
}

func testHTTPSubmit() {
	quickWS("u10003")

	payload := map[string]any{
		"studentId": "u10003",
		"examId":    "1001",
	}
	resp, data := httpPost(baseURL+"/api/proctor/v1/answers/submit", payload)

	if resp == nil {
		fail("POST /answers/submit", "连接失败")
		return
	}
	if resp.StatusCode != 200 {
		fail("POST /answers/submit", "状态码 %d, body: %s", resp.StatusCode, string(data))
		return
	}

	var result map[string]any
	json.Unmarshal(data, &result)
	if result["message"] != "exam submitted" {
		fail("POST /answers/submit", "响应不正确: %s", string(data))
		return
	}
	ok("POST /answers/submit")
}

func quickWS(studentID string) {
	url := fmt.Sprintf("%s?studentId=%s&recordId=777", wsURL, studentID)
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		return
	}
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	conn.ReadMessage()
	conn.Close()
}
