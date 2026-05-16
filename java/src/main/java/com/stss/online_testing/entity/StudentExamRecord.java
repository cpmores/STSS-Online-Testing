package com.stss.online_testing.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("student_exam_record")
public class StudentExamRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long examId;
    private Long studentId;
    private Long courseId;
    private Integer totalScore;
    
    // 0-批改中/考试中, 1-已完成评分, 2-异常作废
    private Integer status;
    private Date submitTime;
    
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
    @TableLogic
    private Integer isDeleted;
}