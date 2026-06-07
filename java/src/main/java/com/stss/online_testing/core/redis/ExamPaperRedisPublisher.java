package com.stss.online_testing.core.redis;

import com.stss.online_testing.entity.ExamPaper;
import com.stss.online_testing.entity.ExamPaperQuestion;
import com.stss.online_testing.entity.Question;
import com.stss.online_testing.common.exception.ApiBusinessException;
import com.stss.online_testing.mapper.ExamPaperQuestionMapper;
import com.stss.online_testing.mapper.QuestionMapper;
import com.stss.online_testing.service.IExamPaperService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "proctor.redis.enabled", havingValue = "true", matchIfMissing = false)
public class ExamPaperRedisPublisher {

    private static final Logger log = LoggerFactory.getLogger(ExamPaperRedisPublisher.class);
    private static final String KEY_PREFIX = "exam:";
    private static final String KEY_SUFFIX = ":paper";

    private final StringRedisTemplate redisTemplate;
    private final IExamPaperService examPaperService;
    private final ExamPaperQuestionMapper examPaperQuestionMapper;
    private final QuestionMapper questionMapper;
    private final ObjectMapper objectMapper;

    public ExamPaperRedisPublisher(
            StringRedisTemplate redisTemplate,
            IExamPaperService examPaperService,
            ExamPaperQuestionMapper examPaperQuestionMapper,
            QuestionMapper questionMapper,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.examPaperService = examPaperService;
        this.examPaperQuestionMapper = examPaperQuestionMapper;
        this.questionMapper = questionMapper;
        this.objectMapper = objectMapper;
    }

    public void publishExamPaper(Long examId) {
        try {
            ExamPaper paper = examPaperService.getById(examId);
            if (paper == null) {
                throw ApiBusinessException.notFound("试卷不存在，无法发布到 Redis");
            }

            List<ExamPaperQuestion> questions = examPaperQuestionMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ExamPaperQuestion>()
                            .eq(ExamPaperQuestion::getPaperId, examId)
                            .orderByAsc(ExamPaperQuestion::getSortOrder));

            List<Map<String, Object>> questionList = new ArrayList<>();
            for (ExamPaperQuestion epq : questions) {
                Question q = questionMapper.selectById(epq.getQuestionId());
                if (q == null) continue;

                Map<String, Object> qMap = new LinkedHashMap<>();
                qMap.put("questionId", q.getId());
                qMap.put("type", q.getType());
                qMap.put("score", epq.getScore());
                qMap.put("stem", q.getStem());
                qMap.put("options", q.getOptions() == null ? Collections.emptyList() : q.getOptions());
                qMap.put("answer", q.getAnswer());
                qMap.put("sortOrder", epq.getSortOrder());
                questionList.add(qMap);
            }

            Map<String, Object> paperData = new LinkedHashMap<>();
            paperData.put("examId", paper.getId());
            paperData.put("courseId", paper.getCourseId());
            paperData.put("title", paper.getTitle());
            paperData.put("totalScore", paper.getTotalScore());
            paperData.put("durationMins", paper.getDurationMins());
            paperData.put("passScore", paper.getPassScore());
            paperData.put("validStartTime", toEpochSeconds(paper.getValidStartTime()));
            paperData.put("validEndTime", toEpochSeconds(paper.getValidEndTime()));
            paperData.put("questions", questionList);

            String json = objectMapper.writeValueAsString(paperData);
            String key = KEY_PREFIX + examId + KEY_SUFFIX;

            Duration ttl = Duration.ofHours(24);
            if (paper.getValidEndTime() != null) {
                long endTimeEpoch = toEpochSeconds(paper.getValidEndTime());
                long nowEpoch = Instant.now().getEpochSecond();
                ttl = Duration.ofSeconds(endTimeEpoch - nowEpoch + 86400);
                if (ttl.isNegative()) ttl = Duration.ofHours(1);
            }

            redisTemplate.opsForValue().set(key, json, ttl);
            log.info("Published exam paper to Redis: examId={}, ttl={}s", examId, ttl.getSeconds());

        } catch (ApiBusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to publish exam paper to Redis: examId={}", examId, e);
            throw ApiBusinessException.unprocessable("发布试卷快照到 Redis 失败: " + e.getMessage());
        }
    }

    private long toEpochSeconds(Date date) {
        if (date == null) return 0;
        return date.getTime() / 1000;
    }
}
