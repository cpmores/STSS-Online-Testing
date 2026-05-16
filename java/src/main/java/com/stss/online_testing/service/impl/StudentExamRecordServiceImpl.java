package com.stss.online_testing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stss.online_testing.common.exception.ApiBusinessException;
import com.stss.online_testing.dto.ExamSubmitReq;
import com.stss.online_testing.entity.ExamPaperQuestion;
import com.stss.online_testing.entity.Question;
import com.stss.online_testing.entity.StudentExamAnswer;
import com.stss.online_testing.entity.StudentExamRecord;
import com.stss.online_testing.mapper.ExamPaperQuestionMapper;
import com.stss.online_testing.mapper.QuestionMapper;
import com.stss.online_testing.mapper.StudentExamAnswerMapper;
import com.stss.online_testing.mapper.StudentExamRecordMapper;
import com.stss.online_testing.service.IStudentExamRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class StudentExamRecordServiceImpl extends ServiceImpl<StudentExamRecordMapper, StudentExamRecord> implements IStudentExamRecordService {

    @Autowired
    private ExamPaperQuestionMapper examPaperQuestionMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private StudentExamAnswerMapper studentExamAnswerMapper;

    @Override
    @Transactional(rollbackFor = Exception.class) // 保证主表和明细表同时成功或同时失败
    public StudentExamRecord calculateAndSubmit(ExamSubmitReq req) {
        
        // 1. 获取试卷中所有题目的分值设定
        QueryWrapper<ExamPaperQuestion> relationWrapper = new QueryWrapper<>();
        relationWrapper.eq("paper_id", req.getExamId());
        List<ExamPaperQuestion> relations = examPaperQuestionMapper.selectList(relationWrapper);
        if (relations.isEmpty()) {
            throw ApiBusinessException.unprocessable("试卷结构异常，未找到题目");
        }
        // 将结构转为 Map: {questionId -> score}，方便后续快速查分
        Map<Long, Integer> questionScoreMap = relations.stream()
                .collect(Collectors.toMap(ExamPaperQuestion::getQuestionId, ExamPaperQuestion::getScore));

        // 2. 批量获取这些题目的标准答案
        List<Long> questionIds = new ArrayList<>(questionScoreMap.keySet());
        List<Question> questions = questionMapper.selectBatchIds(questionIds);
        // 将结构转为 Map: {questionId -> answer}
        Map<Long, String> standardAnswerMap = questions.stream()
                .collect(Collectors.toMap(Question::getId, Question::getAnswer));

        // 3. 开始执行自动评分 (Calculate)
        int totalScore = 0;
        List<StudentExamAnswer> answerDetails = new ArrayList<>();
        
        for (ExamSubmitReq.AnswerItem item : req.getAnswers()) {
            Long qId = item.getQuestionId();
            String studentAns = item.getStudentAnswer();
            String standardAns = standardAnswerMap.get(qId);
            
            StudentExamAnswer detail = new StudentExamAnswer();
            detail.setQuestionId(qId);
            detail.setStudentAnswer(studentAns);
            
            // 判卷逻辑：忽略大小写和前后空格的精确匹配
            if (standardAns != null && studentAns != null && standardAns.trim().equalsIgnoreCase(studentAns.trim())) {
                detail.setIsCorrect(1);
                int earnedScore = questionScoreMap.getOrDefault(qId, 0);
                detail.setScore(earnedScore);
                totalScore += earnedScore; // 累加总分
            } else {
                detail.setIsCorrect(0);
                detail.setScore(0);
            }
            answerDetails.add(detail);
        }

        // 4. 保存考试记录主表
        StudentExamRecord record = new StudentExamRecord();
        record.setExamId(req.getExamId());
        record.setStudentId(req.getStudentId());
        record.setCourseId(req.getCourseId());
        record.setTotalScore(totalScore);
        record.setStatus(1); // 标记为已完成评分
        record.setSubmitTime(new Date());
        this.save(record); // 插入后拿到自增的主表 ID

        // 5. 绑定记录ID，并保存答题明细
        for (StudentExamAnswer detail : answerDetails) {
            detail.setRecordId(record.getId());
            studentExamAnswerMapper.insert(detail); // 若数据量大，后续可优化为批量插入
        }

        return record;
    }

    @Autowired
    private com.stss.online_testing.mapper.ExamPaperMapper examPaperMapper;

    @Override
    public com.stss.online_testing.dto.ExamStatsVO getExamStats(Long examId) {
        com.stss.online_testing.entity.ExamPaper paper = examPaperMapper.selectById(examId);
        if (paper == null) {
            throw ApiBusinessException.notFound("试卷不存在或已删除");
        }
        int passScore = paper.getPassScore() != null ? paper.getPassScore() : 60;

        QueryWrapper<StudentExamRecord> recordWrapper = new QueryWrapper<>();
        recordWrapper.eq("exam_id", examId).eq("status", 1).eq("is_deleted", 0).orderByDesc("total_score").orderByAsc("submit_time");
        List<StudentExamRecord> records = this.baseMapper.selectList(recordWrapper);

        com.stss.online_testing.dto.ExamStatsVO vo = new com.stss.online_testing.dto.ExamStatsVO();
        vo.setExamId(examId);
        vo.setAttendCount(records.size());
        vo.setScoreRanges(buildScoreRanges(records));
        vo.setRankings(buildRankings(records));
        vo.setQuestionStats(buildQuestionStats(examId, records));

        if (records.isEmpty()) {
            vo.setMaxScore(0);
            vo.setMinScore(0);
            vo.setAvgScore(0.0);
            vo.setStdDeviation(0.0);
            vo.setPassCount(0);
            vo.setPassRate(0.0);
            return vo;
        }

        IntSummaryStatistics summary = records.stream()
                .map(StudentExamRecord::getTotalScore)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .summaryStatistics();
        double avg = records.stream()
                .map(StudentExamRecord::getTotalScore)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);
        long passCount = records.stream()
                .map(StudentExamRecord::getTotalScore)
                .filter(Objects::nonNull)
                .filter(score -> score >= passScore)
                .count();
        double variance = records.stream()
                .map(StudentExamRecord::getTotalScore)
                .filter(Objects::nonNull)
                .mapToDouble(score -> Math.pow(score - avg, 2))
                .average()
                .orElse(0.0);

        vo.setMaxScore(summary.getMax());
        vo.setMinScore(summary.getMin());
        vo.setAvgScore(avg);
        vo.setStdDeviation(Math.sqrt(variance));
        vo.setPassCount((int) passCount);
        vo.setPassRate(passCount * 100.0 / records.size());
        return vo;
    }

    private List<com.stss.online_testing.dto.ExamStatsVO.ScoreRangeVO> buildScoreRanges(
            List<StudentExamRecord> records) {
        int[] buckets = new int[5];
        for (StudentExamRecord record : records) {
            int score = record.getTotalScore() == null ? 0 : record.getTotalScore();
            if (score < 60) {
                buckets[0]++;
            } else if (score < 70) {
                buckets[1]++;
            } else if (score < 80) {
                buckets[2]++;
            } else if (score < 90) {
                buckets[3]++;
            } else {
                buckets[4]++;
            }
        }

        String[] labels = {"0-59", "60-69", "70-79", "80-89", "90+"};
        List<com.stss.online_testing.dto.ExamStatsVO.ScoreRangeVO> ranges = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            com.stss.online_testing.dto.ExamStatsVO.ScoreRangeVO range =
                    new com.stss.online_testing.dto.ExamStatsVO.ScoreRangeVO();
            range.setLabel(labels[i]);
            range.setCount(buckets[i]);
            ranges.add(range);
        }
        return ranges;
    }

    private List<com.stss.online_testing.dto.ExamStatsVO.RankingVO> buildRankings(
            List<StudentExamRecord> records) {
        return records.stream()
                .map(
                        record -> {
                            com.stss.online_testing.dto.ExamStatsVO.RankingVO ranking =
                                    new com.stss.online_testing.dto.ExamStatsVO.RankingVO();
                            ranking.setRecordId(record.getId());
                            ranking.setStudentId(record.getStudentId());
                            ranking.setTotalScore(record.getTotalScore());
                            ranking.setSubmitTime(record.getSubmitTime());
                            return ranking;
                        })
                .collect(Collectors.toList());
    }

    private List<com.stss.online_testing.dto.ExamStatsVO.QuestionStatsVO> buildQuestionStats(
            Long examId, List<StudentExamRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }

        QueryWrapper<ExamPaperQuestion> relationWrapper = new QueryWrapper<>();
        relationWrapper.eq("paper_id", examId).orderByAsc("sort_order");
        List<ExamPaperQuestion> relations = examPaperQuestionMapper.selectList(relationWrapper);
        if (relations.isEmpty()) {
            return List.of();
        }

        List<Long> recordIds = records.stream().map(StudentExamRecord::getId).toList();
        QueryWrapper<StudentExamAnswer> answerWrapper = new QueryWrapper<>();
        answerWrapper.in("record_id", recordIds);
        List<StudentExamAnswer> answers = studentExamAnswerMapper.selectList(answerWrapper);
        Map<Long, List<StudentExamAnswer>> answersByQuestion = answers.stream()
                .collect(Collectors.groupingBy(StudentExamAnswer::getQuestionId));

        List<Long> questionIds = relations.stream().map(ExamPaperQuestion::getQuestionId).toList();
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(questionIds).stream()
                .collect(Collectors.toMap(Question::getId, question -> question));

        List<com.stss.online_testing.dto.ExamStatsVO.QuestionStatsVO> questionStats = new ArrayList<>();
        for (ExamPaperQuestion relation : relations) {
            List<StudentExamAnswer> questionAnswers =
                    answersByQuestion.getOrDefault(relation.getQuestionId(), List.of());

            long correctCount = questionAnswers.stream()
                    .filter(answer -> Integer.valueOf(1).equals(answer.getIsCorrect()))
                    .count();
            long answeredCount = questionAnswers.stream()
                    .filter(answer -> answer.getStudentAnswer() != null && !answer.getStudentAnswer().isBlank())
                    .count();
            Map<String, Long> distribution = questionAnswers.stream()
                    .filter(answer -> answer.getStudentAnswer() != null && !answer.getStudentAnswer().isBlank())
                    .collect(Collectors.groupingBy(StudentExamAnswer::getStudentAnswer, LinkedHashMap::new, Collectors.counting()));

            com.stss.online_testing.dto.ExamStatsVO.QuestionStatsVO questionStatsVO =
                    new com.stss.online_testing.dto.ExamStatsVO.QuestionStatsVO();
            questionStatsVO.setQuestionId(relation.getQuestionId());
            questionStatsVO.setSortOrder(relation.getSortOrder());
            questionStatsVO.setStem(
                    questionMap.get(relation.getQuestionId()) == null
                            ? null
                            : questionMap.get(relation.getQuestionId()).getStem());
            questionStatsVO.setCorrectRate(
                    answeredCount == 0 ? 0.0 : correctCount * 100.0 / answeredCount);
            questionStatsVO.setOptionDistribution(distribution);
            questionStats.add(questionStatsVO);
        }
        return questionStats;
    }
}
