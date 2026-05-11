package com.stss.online_testing.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class ExamStatsVO {
    private Long examId;
    private Integer attendCount;   // 参加考试人数
    private Integer maxScore;      // 最高分
    private Integer minScore;      // 最低分
    private Double avgScore;       // 平均分
    private Double stdDeviation;   // 标准差
    private Integer passCount;     // 及格人数
    private Double passRate;       // 及格率 (百分比)
    private List<ScoreRangeVO> scoreRanges;
    private List<RankingVO> rankings;
    private List<QuestionStatsVO> questionStats;

    @Data
    public static class ScoreRangeVO {
        private String label;
        private Integer count;
    }

    @Data
    public static class RankingVO {
        private Long recordId;
        private Long studentId;
        private Integer totalScore;
        private java.util.Date submitTime;
    }

    @Data
    public static class QuestionStatsVO {
        private Long questionId;
        private Integer sortOrder;
        private String stem;
        private Double correctRate;
        private Map<String, Long> optionDistribution;
    }
}
