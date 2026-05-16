package com.stss.online_testing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

@Data
@TableName("exam_runtime_config")
public class ExamRuntimeConfig {
    @TableId(type = IdType.INPUT)
    private Long examId;

    private Integer allowedAttempts;
    private Integer scoreVisible;
    private Integer answerVisible;
    private String scoringStrategy;
    private Date createTime;
    private Date updateTime;
}
