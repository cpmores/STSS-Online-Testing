package com.stss.online_testing.dto;

import java.util.Date;
import lombok.Data;
import java.util.List;

@Data
public class ExamGenerateReq {
    private Long id;
    private Long courseId;
    private String title;
    private Integer totalScore;
    private Integer durationMins;
    private Integer passScore;
    private Integer allowedAttempts;
    private String scoringStrategy;
    private Date validStartTime;
    private Date validEndTime;
    
    // "manual" 或 "auto"
    private String generateMode;
    
    // 手工组卷时的题目ID列表
    private List<Long> questionIds;
    private List<Integer> questionScores;
    
    // 自动组卷规则对象 (内部类)
    private AutoRules autoRules;

    @Data
    public static class AutoRules {
        private Integer singleChoiceCount;
        private Integer trueFalseCount;
        private Integer singleChoiceScore;
        private Integer trueFalseScore;
        private Integer targetDifficulty;
        private List<String> knowledgePoints;
    }
}
