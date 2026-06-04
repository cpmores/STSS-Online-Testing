package main

import (
	"testing"
	"time"

	pb "ot-go/pkg/proctor/v1"
)

func TestComputeSessionExpireTimeClampsToExamEnd(t *testing.T) {
	startTime := time.Date(2026, 6, 4, 17, 5, 28, 0, time.UTC)
	validEndTime := time.Date(2026, 6, 4, 17, 5, 59, 0, time.UTC)

	sm := &SessionManager{
		paper: &pb.ExamPaper{
			DurationMins: 60,
			ValidEndTime: validEndTime.Unix(),
		},
	}

	expireTime := sm.computeSessionExpireTime(startTime)
	if !expireTime.Equal(validEndTime) {
		t.Fatalf("expected expireTime %s, got %s", validEndTime, expireTime)
	}
}

func TestEffectiveExpireTimeKeepsEarlierStoredDeadline(t *testing.T) {
	startTime := time.Date(2026, 6, 4, 9, 5, 28, 0, time.UTC)
	storedExpireTime := time.Date(2026, 6, 4, 9, 30, 0, 0, time.UTC)
	validEndTime := time.Date(2026, 6, 4, 10, 5, 59, 0, time.UTC)

	sm := &SessionManager{
		paper: &pb.ExamPaper{
			DurationMins: 60,
			ValidEndTime: validEndTime.Unix(),
		},
	}

	expireTime, err := sm.effectiveExpireTime(map[string]string{
		"startTime":  startTime.Format(time.RFC3339),
		"expireTime": storedExpireTime.Format(time.RFC3339),
	})
	if err != nil {
		t.Fatalf("effectiveExpireTime returned error: %v", err)
	}
	if !expireTime.Equal(storedExpireTime) {
		t.Fatalf("expected expireTime %s, got %s", storedExpireTime, expireTime)
	}
}

func TestSessionTTLRetainsOneHourAfterDeadline(t *testing.T) {
	sm := &SessionManager{}
	expireTime := time.Now().Add(-30 * time.Minute)

	ttl := sm.sessionTTL(expireTime)
	if ttl < time.Hour {
		t.Fatalf("expected ttl >= 1h, got %s", ttl)
	}
}
