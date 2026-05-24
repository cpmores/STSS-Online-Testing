package models

import "time"

// 题目类型常量，与 Java 侧保持一致
const (
	QuestionTypeSingleChoice = 1 // 单选题
	QuestionTypeTrueFalse    = 2 // 是非题
)

// Exam 代表一场考试的基本信息，与 Java ExamPaper 对应。
type Exam struct {
	ID             int64     `json:"examId"`
	CourseID       int64     `json:"courseId"`
	Title          string    `json:"title"`
	TotalScore     int       `json:"totalScore"`
	DurationMins   int       `json:"durationMins"`
	PassScore      int       `json:"passScore"`
	Status         int       `json:"status"` // 0-草稿, 1-已发布, 2-已撤回
	ValidStartTime time.Time `json:"validStartTime"`
	ValidEndTime   time.Time `json:"validEndTime"`
}

// Question 完整题目模型（含答案，仅内部/评分使用，不可返回给学生）。
type Question struct {
	ID              int64    `json:"id" gorm:"primarykey"`
	CourseID        int64    `json:"courseId"`
	Type            int      `json:"type"` // 1-单选, 2-是非
	Stem            string   `json:"stem"`
	Options         []string `json:"options,omitempty"`
	Answer          string   `json:"answer"`       // 标准答案
	Difficulty      int      `json:"difficulty"`   // 1-简单, 2-中等, 3-困难
	KnowledgePoints []string `json:"knowledgePoints"`
}

// QuestionVO 学生端题目视图（不含答案和解析）。
type QuestionVO struct {
	QuestionID int64    `json:"questionId"`
	Type       int      `json:"type"`
	Score      int      `json:"score"`
	Stem       string   `json:"stem"`
	Options    []string `json:"options,omitempty"`
	SortOrder  int      `json:"sortOrder"`
}

// ExamPaperQuestion 试卷-题目关联，与 Java ExamPaperQuestion 对应。
type ExamPaperQuestion struct {
	ID         int64 `gorm:"primarykey"`
	PaperID    int64 `gorm:"index"`
	QuestionID int64
	Score      int
	SortOrder  int
}

// ExamSession 考试会话，表示学生在 Redis 中的考试状态。
type ExamSession struct {
	RecordID       int64        `json:"recordId"`
	StudentID      int64        `json:"studentId"`
	ExamID         int64        `json:"examId"`
	StartTime      time.Time    `json:"startTime"`
	ExpireTime     time.Time    `json:"expireTime"`
	Status         int          `json:"status"` // 0-考试中, 1-已交卷
	Questions      []QuestionVO `json:"questions"`
	AllowedAttempts int         `json:"allowedAttempts"`
}

// ExamResult 考试结果主记录，与 Java StudentExamRecord 对应。
type ExamResult struct {
	ID         int64     `gorm:"primarykey"`
	StudentID  int64     `gorm:"index"`
	ExamID     int64     `gorm:"index"`
	CourseID   int64
	TotalScore int       `gorm:"default:0"`
	Status     int       // 0-考试中, 1-已完成评分, 2-异常作废
	SubmitTime time.Time `gorm:"default:null"`
	CreatedAt  time.Time
	UpdatedAt  time.Time
}

// AnswerRecord 单题作答明细，与 Java StudentExamAnswer 对应。
type AnswerRecord struct {
	ID            int64  `gorm:"primarykey"`
	RecordID      int64  `gorm:"index"`
	QuestionID    int64
	StudentAnswer string // 空字符串表示未作答
	IsCorrect     int    // 0-错误, 1-正确
	Score         int    `gorm:"default:0"`
}

// ExamRuntimeConfig 考试运行时配置，与 Java ExamRuntimeConfig 对应。
type ExamRuntimeConfig struct {
	ExamID          int64  `gorm:"primarykey"`
	AllowedAttempts int    `gorm:"default:1"`
	ScoringStrategy string `gorm:"default:AUTO_GRADE"`
	ScoreVisible    int    // 0-不可见, 1-可见
	AnswerVisible   int    // 0-不可见, 1-可见
}
