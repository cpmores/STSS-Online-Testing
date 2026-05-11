package com.stss.online_testing.dto;

import lombok.Data;
import java.util.List;

@Data
public class ExamSubmitReq {
    private Long examId;
    private Long studentId;
    private Long courseId;
    
    // 学生的具体答题明细
    private List<AnswerItem> answers;

    @Data
    public static class AnswerItem {
        private Long questionId;
        // 学生提交的答案（比如 "A", "True" 等）
        private String studentAnswer; 
    }
}