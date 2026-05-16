package com.stss.online_testing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stss.online_testing.common.exception.ApiBusinessException;
import com.stss.online_testing.dto.ExamGenerateReq;
import com.stss.online_testing.dto.ExamPaperStudentVO;
import com.stss.online_testing.entity.ExamPaper;
import com.stss.online_testing.entity.ExamPaperQuestion;
import com.stss.online_testing.entity.Question;
import com.stss.online_testing.mapper.ExamPaperMapper;
import com.stss.online_testing.mapper.ExamPaperQuestionMapper;
import com.stss.online_testing.mapper.QuestionMapper;
import com.stss.online_testing.service.IExamPaperService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamPaperServiceImpl extends ServiceImpl<ExamPaperMapper, ExamPaper>
        implements IExamPaperService {

    @Autowired
    private ExamPaperQuestionMapper examPaperQuestionMapper;

    @Autowired
    private QuestionMapper questionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long generateExam(ExamGenerateReq req, Long operatorId) {
        if (req == null) {
            throw ApiBusinessException.badRequest("组卷请求不能为空");
        }
        if (operatorId == null) {
            throw ApiBusinessException.forbidden("当前教师身份缺失");
        }
        if (req.getId() != null && req.getId() <= 0) {
            throw ApiBusinessException.badRequest("试卷 id 必须为正整数");
        }

        boolean isUpdate = req.getId() != null;
        ExamPaper examPaper = isUpdate ? this.getById(req.getId()) : new ExamPaper();
        if (isUpdate && examPaper == null) {
            throw ApiBusinessException.notFound("待更新的试卷不存在或已删除");
        }
        if (isUpdate && !Objects.equals(examPaper.getCreatorId(), operatorId)) {
            throw ApiBusinessException.forbidden("无权修改其他教师创建的试卷");
        }
        if (isUpdate && Integer.valueOf(1).equals(examPaper.getStatus())) {
            throw ApiBusinessException.conflict("已发布试卷不能直接编辑，请先撤回后再修改");
        }

        validateExamRequest(req);
        validateQuestionsForRequest(req);

        BeanUtils.copyProperties(req, examPaper);
        examPaper.setCreatorId(isUpdate ? examPaper.getCreatorId() : operatorId);
        examPaper.setStatus(isUpdate ? examPaper.getStatus() : 0);

        if (isUpdate) {
            if (!this.updateById(examPaper)) {
                throw ApiBusinessException.unprocessable("试卷更新失败");
            }
        } else {
            if (!this.save(examPaper)) {
                throw ApiBusinessException.unprocessable("试卷创建失败");
            }
        }

        Long examId = examPaper.getId();
        rebuildPaperQuestions(examId, req);
        return examId;
    }

    private void handleManualGeneration(Long examId, ExamGenerateReq req) {
        List<Long> questionIds = req.getQuestionIds();
        List<Integer> questionScores = req.getQuestionScores();
        Integer defaultScorePerQuestion =
                questionScores == null
                        ? calculateDefaultScore(req.getTotalScore(), questionIds.size())
                        : null;

        for (int i = 0; i < questionIds.size(); i++) {
            ExamPaperQuestion relation = new ExamPaperQuestion();
            relation.setPaperId(examId);
            relation.setQuestionId(questionIds.get(i));
            relation.setScore(questionScores == null ? defaultScorePerQuestion : questionScores.get(i));
            relation.setSortOrder(i + 1);
            examPaperQuestionMapper.insert(relation);
        }
    }

    private void handleAutoGeneration(Long examId, ExamGenerateReq req) {
        ExamGenerateReq.AutoRules rules = req.getAutoRules();
        int singleCount = rules.getSingleChoiceCount() == null ? 0 : rules.getSingleChoiceCount();
        int tfCount = rules.getTrueFalseCount() == null ? 0 : rules.getTrueFalseCount();
        int singleScore = rules.getSingleChoiceScore() == null ? 0 : rules.getSingleChoiceScore();
        int tfScore = rules.getTrueFalseScore() == null ? 0 : rules.getTrueFalseScore();

        int sortOrder = 1;
        if (singleCount > 0) {
            List<Long> singleIds = getRandomQuestionIds(req.getCourseId(), 1, singleCount, rules);
            for (Long qId : singleIds) {
                insertRelation(examId, qId, singleScore, sortOrder++);
            }
        }
        if (tfCount > 0) {
            List<Long> tfIds = getRandomQuestionIds(req.getCourseId(), 2, tfCount, rules);
            for (Long qId : tfIds) {
                insertRelation(examId, qId, tfScore, sortOrder++);
            }
        }
    }

    private void insertRelation(Long paperId, Long questionId, int score, int sortOrder) {
        ExamPaperQuestion relation = new ExamPaperQuestion();
        relation.setPaperId(paperId);
        relation.setQuestionId(questionId);
        relation.setScore(score);
        relation.setSortOrder(sortOrder);
        examPaperQuestionMapper.insert(relation);
    }

    private void rebuildPaperQuestions(Long examId, ExamGenerateReq req) {
        QueryWrapper<ExamPaperQuestion> deleteWrapper = new QueryWrapper<>();
        deleteWrapper.eq("paper_id", examId);
        examPaperQuestionMapper.delete(deleteWrapper);

        if ("manual".equals(req.getGenerateMode())) {
            handleManualGeneration(examId, req);
            return;
        }
        if ("auto".equals(req.getGenerateMode())) {
            handleAutoGeneration(examId, req);
            return;
        }
        throw ApiBusinessException.badRequest("未知的组卷模式: " + req.getGenerateMode());
    }

    private void validateExamRequest(ExamGenerateReq req) {
        if (req.getCourseId() == null) {
            throw ApiBusinessException.badRequest("所属课程不能为空");
        }
        if (req.getCourseId() <= 0) {
            throw ApiBusinessException.badRequest("所属课程 id 必须为正整数");
        }
        if (req.getGenerateMode() == null || req.getGenerateMode().isBlank()) {
            throw ApiBusinessException.badRequest("组卷模式不能为空");
        }
        req.setGenerateMode(req.getGenerateMode().trim().toLowerCase());
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw ApiBusinessException.badRequest("试卷名称不能为空");
        }
        req.setTitle(req.getTitle().trim());
        if (req.getTotalScore() == null || req.getTotalScore() <= 0) {
            throw ApiBusinessException.badRequest("试卷总分必须大于 0");
        }
        if (req.getDurationMins() == null || req.getDurationMins() <= 0) {
            throw ApiBusinessException.badRequest("考试时长必须大于 0");
        }
        if (req.getPassScore() == null) {
            req.setPassScore(60);
        } else if (req.getPassScore() <= 0 || req.getPassScore() > req.getTotalScore()) {
            throw ApiBusinessException.unprocessable("及格分必须大于 0 且不能超过试卷总分");
        }
        if (req.getAllowedAttempts() == null) {
            req.setAllowedAttempts(1);
        } else if (req.getAllowedAttempts() <= 0) {
            throw ApiBusinessException.badRequest("允许考试次数必须大于 0");
        }
        if (req.getScoringStrategy() == null || req.getScoringStrategy().isBlank()) {
            req.setScoringStrategy("AUTO_GRADE");
        } else {
            req.setScoringStrategy(req.getScoringStrategy().trim());
        }
    }

    private void validateQuestionsForRequest(ExamGenerateReq req) {
        if ("manual".equals(req.getGenerateMode())) {
            validateManualQuestions(req);
            return;
        }
        if ("auto".equals(req.getGenerateMode())) {
            validateAutoQuestions(req);
            return;
        }
        throw ApiBusinessException.badRequest("未知的组卷模式: " + req.getGenerateMode());
    }

    private void validateManualQuestions(ExamGenerateReq req) {
        List<Long> questionIds = req.getQuestionIds();
        if (questionIds == null || questionIds.isEmpty()) {
            throw ApiBusinessException.unprocessable("手工组卷必须提供题目列表");
        }
        if (new HashSet<>(questionIds).size() != questionIds.size()) {
            throw ApiBusinessException.conflict("手工组卷题目列表中存在重复题目 id");
        }
        if (questionIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw ApiBusinessException.badRequest("题目 id 必须为正整数");
        }

        List<Question> questions = questionMapper.selectBatchIds(questionIds);
        Map<Long, Question> questionMap =
                questions.stream().collect(Collectors.toMap(Question::getId, question -> question));
        if (questionMap.size() != questionIds.size()) {
            List<Long> missing = new ArrayList<>();
            for (Long questionId : questionIds) {
                if (!questionMap.containsKey(questionId)) {
                    missing.add(questionId);
                }
            }
            throw ApiBusinessException.notFound("以下题目不存在或已删除: " + missing);
        }

        for (Question question : questions) {
            if (!Objects.equals(question.getCourseId(), req.getCourseId())) {
                throw ApiBusinessException.unprocessable(
                        "题目 " + question.getId() + " 不属于课程 " + req.getCourseId());
            }
            if (question.getType() == null || (question.getType() != 1 && question.getType() != 2)) {
                throw ApiBusinessException.unprocessable("题目 " + question.getId() + " 的题型不受支持");
            }
        }

        List<Integer> questionScores = req.getQuestionScores();
        if (questionScores != null) {
            if (questionScores.size() != questionIds.size()) {
                throw ApiBusinessException.unprocessable("题目分值列表必须与题目列表一一对应");
            }
            if (questionScores.stream().anyMatch(score -> score == null || score <= 0)) {
                throw ApiBusinessException.unprocessable("题目分值必须为正整数");
            }
            int total = questionScores.stream().mapToInt(Integer::intValue).sum();
            if (!Objects.equals(total, req.getTotalScore())) {
                throw ApiBusinessException.unprocessable("题目分值之和必须等于试卷总分");
            }
        } else if (req.getTotalScore() % questionIds.size() != 0) {
            throw ApiBusinessException.unprocessable("当前算法要求试卷总分必须能被总题数整除");
        }
    }

    private void validateAutoQuestions(ExamGenerateReq req) {
        ExamGenerateReq.AutoRules rules = req.getAutoRules();
        if (rules == null) {
            throw ApiBusinessException.unprocessable("自动组卷规则不能为空");
        }
        int singleCount = rules.getSingleChoiceCount() == null ? 0 : rules.getSingleChoiceCount();
        int tfCount = rules.getTrueFalseCount() == null ? 0 : rules.getTrueFalseCount();
        int singleScore = rules.getSingleChoiceScore() == null ? 0 : rules.getSingleChoiceScore();
        int tfScore = rules.getTrueFalseScore() == null ? 0 : rules.getTrueFalseScore();
        if (singleCount < 0 || tfCount < 0) {
            throw ApiBusinessException.unprocessable("抽题数量不能为负数");
        }
        if (singleCount + tfCount == 0) {
            throw ApiBusinessException.unprocessable("抽题总数不能为0");
        }
        if (singleCount > 0 && singleScore <= 0) {
            throw ApiBusinessException.unprocessable("存在单选题抽题需求时，单选题分值必须大于 0");
        }
        if (singleCount == 0 && rules.getSingleChoiceScore() != null && singleScore != 0) {
            throw ApiBusinessException.unprocessable("未抽取单选题时，单选题分值必须为 0 或不传");
        }
        if (tfCount > 0 && tfScore <= 0) {
            throw ApiBusinessException.unprocessable("存在是非题抽题需求时，是非题分值必须大于 0");
        }
        if (tfCount == 0 && rules.getTrueFalseScore() != null && tfScore != 0) {
            throw ApiBusinessException.unprocessable("未抽取是非题时，是非题分值必须为 0 或不传");
        }
        int computedTotalScore = singleCount * singleScore + tfCount * tfScore;
        if (!Objects.equals(computedTotalScore, req.getTotalScore())) {
            throw ApiBusinessException.unprocessable(
                    "自动组卷分值配置不合法：singleChoiceCount * singleChoiceScore + trueFalseCount * trueFalseScore 必须等于试卷总分");
        }
        if (rules.getTargetDifficulty() != null
                && (rules.getTargetDifficulty() < 1 || rules.getTargetDifficulty() > 3)) {
            throw ApiBusinessException.unprocessable("目标难度必须在 1 到 3 之间");
        }
        if (rules.getKnowledgePoints() != null
                && rules.getKnowledgePoints().stream()
                        .anyMatch(point -> point == null || point.isBlank())) {
            throw ApiBusinessException.unprocessable("自动组卷知识点不能为空字符串");
        }
    }

    private int calculateDefaultScore(Integer totalScore, int questionCount) {
        if (questionCount == 0) {
            throw ApiBusinessException.unprocessable("试题数量不能为空");
        }
        if (totalScore % questionCount != 0) {
            throw ApiBusinessException.unprocessable("当前算法要求试卷总分必须能被总题数整除");
        }
        return totalScore / questionCount;
    }

    private List<Long> getRandomQuestionIds(
            Long courseId, Integer type, Integer limit, ExamGenerateReq.AutoRules rules) {
        QueryWrapper<Question> wrapper = new QueryWrapper<>();
        wrapper.eq("course_id", courseId).eq("type", type).eq("is_deleted", 0);

        if (rules.getTargetDifficulty() != null) {
            wrapper.eq("difficulty", rules.getTargetDifficulty());
        }
        if (rules.getKnowledgePoints() != null && !rules.getKnowledgePoints().isEmpty()) {
            wrapper.and(
                    condition -> {
                        for (int i = 0; i < rules.getKnowledgePoints().size(); i++) {
                            String knowledgePoint = rules.getKnowledgePoints().get(i);
                            if (i == 0) {
                                condition.like("knowledge_points", knowledgePoint);
                            } else {
                                condition.or().like("knowledge_points", knowledgePoint);
                            }
                        }
                    });
        }

        List<Question> candidates = questionMapper.selectList(wrapper);
        if (candidates.size() < limit) {
            throw ApiBusinessException.unprocessable(
                    "题库余量不足：课程 "
                            + courseId
                            + " 下类型 "
                            + type
                            + " 的可用题目仅有 "
                            + candidates.size()
                            + " 道");
        }
        Collections.shuffle(candidates);
        return candidates.stream().limit(limit).map(Question::getId).collect(Collectors.toList());
    }

    @Override
    public ExamPaperStudentVO getExamPaperForStudent(Long examId) {
        return buildPaperView(examId, false, null);
    }

    @Override
    public ExamPaperStudentVO getExamPaperForTeacher(Long examId, Long operatorId) {
        if (operatorId == null) {
            throw ApiBusinessException.forbidden("当前教师身份缺失");
        }
        return buildPaperView(examId, true, operatorId);
    }

    private ExamPaperStudentVO buildPaperView(Long examId, boolean teacherPreview, Long operatorId) {
        if (examId == null || examId <= 0) {
            throw ApiBusinessException.badRequest("试卷 id 必须为正整数");
        }

        ExamPaper paper = this.getById(examId);
        if (paper == null) {
            throw ApiBusinessException.notFound("试卷不存在或已删除");
        }
        if (teacherPreview) {
            if (operatorId != null && !Objects.equals(paper.getCreatorId(), operatorId)) {
                throw ApiBusinessException.forbidden("无权预览其他教师创建的试卷");
            }
        } else {
            if (paper.getStatus() != 1) {
                throw ApiBusinessException.conflict("试卷未发布或已撤回，无法参加考试");
            }

            java.util.Date now = new java.util.Date();
            if (paper.getValidStartTime() != null && now.before(paper.getValidStartTime())) {
                throw ApiBusinessException.conflict("考试尚未开始");
            }
            if (paper.getValidEndTime() != null && now.after(paper.getValidEndTime())) {
                throw ApiBusinessException.conflict("考试已经结束");
            }
        }

        QueryWrapper<ExamPaperQuestion> wrapper = new QueryWrapper<>();
        wrapper.eq("paper_id", examId).orderByAsc("sort_order");
        List<ExamPaperQuestion> relations = examPaperQuestionMapper.selectList(wrapper);
        if (relations.isEmpty()) {
            throw ApiBusinessException.unprocessable("试卷未配置题目");
        }

        List<Long> questionIds = relations.stream().map(ExamPaperQuestion::getQuestionId).toList();
        Map<Long, Question> questionMap =
                questionMapper.selectBatchIds(questionIds).stream()
                        .collect(Collectors.toMap(Question::getId, question -> question));
        if (questionMap.size() != questionIds.size()) {
            List<Long> missingQuestionIds = new ArrayList<>();
            for (Long questionId : questionIds) {
                if (!questionMap.containsKey(questionId)) {
                    missingQuestionIds.add(questionId);
                }
            }
            throw ApiBusinessException.unprocessable(
                    "试卷包含已删除或不存在的题目，请重新组卷: " + missingQuestionIds);
        }

        ExamPaperStudentVO vo = new ExamPaperStudentVO();
        vo.setExamId(paper.getId());
        vo.setTitle(paper.getTitle());
        vo.setDurationMins(paper.getDurationMins());

        List<ExamPaperStudentVO.QuestionVO> questionVOs = new ArrayList<>();
        for (ExamPaperQuestion rel : relations) {
            Question q = questionMap.get(rel.getQuestionId());
            ExamPaperStudentVO.QuestionVO qVo = new ExamPaperStudentVO.QuestionVO();
            qVo.setQuestionId(q.getId());
            qVo.setType(q.getType());
            qVo.setScore(rel.getScore());
            qVo.setStem(q.getStem());
            qVo.setOptions(q.getOptions());
            qVo.setSortOrder(rel.getSortOrder());
            questionVOs.add(qVo);
        }
        vo.setQuestions(questionVOs);
        return vo;
    }
}
