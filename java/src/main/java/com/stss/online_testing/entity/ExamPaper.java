package com.stss.online_testing.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data
@TableName("exam_paper")
public class ExamPaper {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long courseId;
    private String title;
    private Integer totalScore;
    private Integer durationMins;
    private Integer passScore;
    
    // 0-草稿, 1-已发布, 2-已撤回
    private Integer status;
    private Long creatorId;
    
    private Date validStartTime;
    private Date validEndTime;
    
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
    @TableLogic
    private Integer isDeleted;

    /** 允许答题次数（来自 exam_runtime_config 表，非本表字段，仅用于传输） */
    @TableField(exist = false)
    private Integer allowedAttempts;
}