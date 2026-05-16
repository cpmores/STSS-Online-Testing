package com.stss.online_testing.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.stss.online_testing.entity.Question;

public interface IQuestionService extends IService<Question> {

    /**
     * 安全删除题目 (带有未开始试卷的风险校验)
     * 返回受影响的试卷名称列表。如果列表为空，说明删除成功。
     */
    List<String> safeDeleteQuestion(Long questionId, boolean force);
    // 基础的增删改查已经由 IService 包含，这里可以留作以后写复杂的自定义业务（如自动组卷抽题逻辑）
}