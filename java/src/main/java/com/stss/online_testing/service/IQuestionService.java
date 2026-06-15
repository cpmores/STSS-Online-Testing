package com.stss.online_testing.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.stss.online_testing.entity.Question;

public interface IQuestionService extends IService<Question> {

    /**
     * 校验题目是否仍被未删除试卷引用。
     */
    void ensureQuestionNotReferenced(Long questionId, String actionLabel);

    /**
     * 安全删除题目。
     */
    List<String> safeDeleteQuestion(Long questionId, boolean force);
}
