package com.stss.online_testing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stss.online_testing.common.exception.ApiBusinessException;
import com.stss.online_testing.entity.ExamPaper;
import com.stss.online_testing.entity.ExamPaperQuestion;
import com.stss.online_testing.entity.Question;
import com.stss.online_testing.mapper.ExamPaperMapper;
import com.stss.online_testing.mapper.ExamPaperQuestionMapper;
import com.stss.online_testing.mapper.QuestionMapper;
import com.stss.online_testing.service.IQuestionService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question> implements IQuestionService {

    @Autowired
    private ExamPaperQuestionMapper examPaperQuestionMapper;

    @Autowired
    private ExamPaperMapper examPaperMapper;

    @Override
    public void ensureQuestionNotReferenced(Long questionId, String actionLabel) {
        if (questionId == null || questionId <= 0) {
            throw ApiBusinessException.badRequest("题目 id 必须为正整数");
        }
        List<String> referencedPaperNames = listReferencedPaperNames(questionId);
        if (!referencedPaperNames.isEmpty()) {
            throw ApiBusinessException.conflict(
                    "题目已被以下试卷引用，无法" + actionLabel + ": " + String.join(", ", referencedPaperNames));
        }
    }

    /**
     * 安全删除题目。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> safeDeleteQuestion(Long questionId, boolean force) {
        if (questionId == null || questionId <= 0) {
            throw ApiBusinessException.badRequest("题目 id 必须为正整数");
        }

        Question question = this.getById(questionId);
        if (question == null) {
            throw ApiBusinessException.notFound("题目不存在或已删除");
        }

        ensureQuestionNotReferenced(questionId, "删除");
        if (!this.removeById(questionId)) {
            throw ApiBusinessException.unprocessable("题目删除失败");
        }

        return new ArrayList<>();
    }

    private List<String> listReferencedPaperNames(Long questionId) {
        QueryWrapper<ExamPaperQuestion> wrapper = new QueryWrapper<>();
        wrapper.eq("question_id", questionId);
        List<ExamPaperQuestion> relations = examPaperQuestionMapper.selectList(wrapper);
        Set<String> paperNames = new LinkedHashSet<>();
        for (ExamPaperQuestion relation : relations) {
            ExamPaper paper = examPaperMapper.selectById(relation.getPaperId());
            if (paper == null || !Objects.equals(paper.getIsDeleted(), 0)) {
                continue;
            }
            paperNames.add(paper.getTitle() == null ? "试卷#" + paper.getId() : paper.getTitle());
        }
        return new ArrayList<>(paperNames);
    }
}
