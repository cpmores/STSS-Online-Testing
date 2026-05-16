package com.stss.online_testing.dto;

import lombok.Data;
import java.util.List;

@Data
public class ExamPaperStudentVO {
    private Long examId;
    private String title;
    private Integer durationMins;
    private List<QuestionVO> questions;

    @Data
    public static class QuestionVO {
        private Long questionId;
        private Integer type;
        private Integer score;
        private String stem;
        private List<String> options;
        private Integer sortOrder;
    }
}