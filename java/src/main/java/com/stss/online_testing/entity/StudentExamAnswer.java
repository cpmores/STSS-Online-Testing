package com.stss.online_testing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("student_exam_answer")
public class StudentExamAnswer {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long recordId;
    private Long questionId;
    private String studentAnswer;
    private Integer isCorrect;
    private Integer score;
}