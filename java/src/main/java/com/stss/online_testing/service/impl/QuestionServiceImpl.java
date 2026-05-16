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
import java.util.Date;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question> implements IQuestionService {

    @Autowired
    private ExamPaperQuestionMapper examPaperQuestionMapper;

    @Autowired
    private ExamPaperMapper examPaperMapper;

    /**
     * 安全删除题目 (带有未开始试卷的风险校验)
     * 返回受影响的试卷名称列表。如果列表为空，说明删除成功。
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

        // 1. 查询该题目被哪些试卷引用了
        QueryWrapper<ExamPaperQuestion> wrapper = new QueryWrapper<>();
        wrapper.eq("question_id", questionId);
        List<ExamPaperQuestion> relations = examPaperQuestionMapper.selectList(wrapper);

        List<String> riskPaperNames = new ArrayList<>();
        Date now = new Date();

        // 2. 检查引用该题目的试卷状态
        for (ExamPaperQuestion rel : relations) {
            ExamPaper paper = examPaperMapper.selectById(rel.getPaperId());
            if (paper != null && Objects.equals(paper.getIsDeleted(), 0)) {
                // 风险条件：试卷是草稿(0)，或者试卷已发布(1)但有效开始时间还没到
                boolean isDraft = Objects.equals(paper.getStatus(), 0);
                boolean isPublishedButNotStarted =
                        Objects.equals(paper.getStatus(), 1)
                                && paper.getValidStartTime() != null
                                && paper.getValidStartTime().after(now);

                if (isDraft || isPublishedButNotStarted) {
                    riskPaperNames.add(paper.getTitle());
                }
            }
        }

        // 3. 如果存在风险且前端没有传递强制删除标志，则直接返回风险列表，中止删除
        if (!riskPaperNames.isEmpty() && !force) {
            throw ApiBusinessException.conflict(
                    "题目被以下未开始试卷引用，无法删除: " + String.join(", ", riskPaperNames));
        }

        // 4. 执行真正的删除 (逻辑删除)
        // 注意：如果是强制删除，我们通常不会去动试卷，只会把题库里的这道题标记为已删除。
        // 未开始的试卷如果抽取了这道题，在学生获取试卷的接口处需要做容错处理（过滤掉被删除的题）。
        if (!this.removeById(questionId)) {
            throw ApiBusinessException.unprocessable("题目删除失败");
        }

        return new ArrayList<>(); // 返回空列表代表成功
    }
}
