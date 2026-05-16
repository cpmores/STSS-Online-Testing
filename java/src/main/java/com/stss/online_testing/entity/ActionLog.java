package com.stss.online_testing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

@Data
@TableName("action_log")
public class ActionLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer level;
    private String service;
    private Integer operationId;
    private String traceId;
    private String userId;
    private String message;
    private String method;
    private String path;
    private Integer statusCode;
    private Long durationMs;
    private String entityType;
    private String entityId;
    private String stringFields;
    private String intFields;
    private String errorMessage;
    private String stackTrace;
    private Integer grpcDelivered;
    private String grpcError;
    private Date createTime;
}
