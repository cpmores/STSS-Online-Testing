package com.stss.online_testing.core.storage;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stss.online_testing.common.exception.ApiBusinessException;
import com.stss.online_testing.dto.ExamGenerateReq;
import com.stss.online_testing.dto.ExamPaperStudentVO;
import com.stss.online_testing.dto.ExamScoreExcelDTO;
import com.stss.online_testing.dto.ExamStatsVO;
import com.stss.online_testing.dto.QuestionExcelDTO;
import com.stss.online_testing.entity.ExamPaper;
import com.stss.online_testing.entity.ExamRuntimeConfig;
import com.stss.online_testing.entity.Question;
import com.stss.online_testing.entity.StudentExamRecord;
import com.stss.online_testing.mapper.ExamRuntimeConfigMapper;
import com.stss.online_testing.mapper.StudentExamRecordMapper;
import com.stss.online_testing.service.IExamPaperService;
import com.stss.online_testing.service.IQuestionService;
import com.stss.online_testing.service.IStudentExamRecordService;
import com.stss.online_testing.service.QuestionImportListener;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class StorageFacade {

    private final IQuestionService questionService;
    private final IExamPaperService examPaperService;
    private final IStudentExamRecordService studentExamRecordService;
    private final StudentExamRecordMapper studentExamRecordMapper;
    private final ExamRuntimeConfigMapper examRuntimeConfigMapper;
    private final ObjectMapper objectMapper;

    public StorageFacade(
            IQuestionService questionService,
            IExamPaperService examPaperService,
            IStudentExamRecordService studentExamRecordService,
            StudentExamRecordMapper studentExamRecordMapper,
            ExamRuntimeConfigMapper examRuntimeConfigMapper,
            ObjectMapper objectMapper) {
        this.questionService = questionService;
        this.examPaperService = examPaperService;
        this.studentExamRecordService = studentExamRecordService;
        this.studentExamRecordMapper = studentExamRecordMapper;
        this.examRuntimeConfigMapper = examRuntimeConfigMapper;
        this.objectMapper = objectMapper;
    }

    public Object execute(StorageCommand command) {
        return switch (command.getAction()) {
            case "add_a_question" -> addQuestion(command);
            case "update_a_question" -> updateQuestion(command);
            case "delete_a_question" -> deleteQuestion(command);
            case "get_a_question" -> getQuestion(command);
            case "query_question_bank" -> queryQuestionBank(command);
            case "import_questions_by_excel" -> importQuestionsByExcel(command);
            case "create_exam_paper", "update_exam_paper", "generate_exam_paper" -> saveExamPaper(command);
            case "delete_exam_paper" -> deleteExamPaper(command);
            case "publish_exam_paper" -> updateExamVisibility(command, 1);
            case "withdraw_exam_paper" -> updateExamVisibility(command, 2);
            case "query_exam_papers" -> queryExamPapers(command);
            case "preview_exam_paper", "get_exam_paper_for_student" -> previewExamPaper(command);
            case "get_exam_stats" -> getExamStats(command);
            case "open_exam_score" -> updateExamScoreVisibility(command);
            case "open_exam_answer" -> updateExamAnswerVisibility(command);
            case "export_exam_scores" -> exportExamScores(command);
            default -> throw new IllegalArgumentException("Storage 不支持的 action: " + command.getAction());
        };
    }

    private Map<String, Object> addQuestion(StorageCommand command) {
        Question question = convertPayload(command.getPayload(), Question.class);
        validateQuestionPayload(question, false);
        question.setCreatorId(command.getOperatorId());
        boolean saved = questionService.save(question);
        if (!saved) {
            throw ApiBusinessException.unprocessable("题目保存失败");
        }
        return Map.of("id", question.getId());
    }

    private Map<String, Object> updateQuestion(StorageCommand command) {
        Question incoming = convertPayload(command.getPayload(), Question.class);
        if (incoming.getId() == null) {
            throw ApiBusinessException.badRequest("更新题目必须传入 id");
        }
        if (incoming.getId() <= 0) {
            throw ApiBusinessException.badRequest("题目 id 必须为正整数");
        }
        validateQuestionPayload(incoming, true);

        Question existing = questionService.getById(incoming.getId());
        if (existing == null) {
            throw ApiBusinessException.notFound("题目不存在或已删除");
        }
        validateQuestionOwnership(existing, command.getOperatorId(), "修改");
        questionService.ensureQuestionNotReferenced(existing.getId(), "修改");

        incoming.setCreatorId(existing.getCreatorId());
        if (incoming.getOptions() == null
                && Objects.equals(incoming.getType(), 2)) {
            incoming.setOptions(List.of("True", "False"));
        }
        boolean updated = questionService.updateById(incoming);
        if (!updated) {
            throw ApiBusinessException.unprocessable("题目更新失败");
        }
        return Map.of("id", incoming.getId());
    }

    private Map<String, Object> deleteQuestion(StorageCommand command) {
        Long questionId = getLong(command.getPayload(), "id");
        boolean force = getBoolean(command.getPayload(), "force", false);
        Question question = questionService.getById(questionId);
        if (question == null) {
            throw ApiBusinessException.notFound("题目不存在或已删除");
        }
        validateQuestionOwnership(question, command.getOperatorId(), "删除");
        questionService.safeDeleteQuestion(questionId, force);
        return Map.of("deleted", true);
    }

    private Question getQuestion(StorageCommand command) {
        Long questionId = getLong(command.getPayload(), "id");
        Question question = questionService.getById(questionId);
        if (question == null) {
            throw ApiBusinessException.notFound("题目不存在或已删除");
        }
        return question;
    }

    private Page<Question> queryQuestionBank(StorageCommand command) {
        Integer current = getInteger(command.getPayload(), "current", 1);
        Integer size = getInteger(command.getPayload(), "size", 10);
        validatePageParams(current, size);
        Long courseId = getOptionalLong(command.getPayload(), "courseId");
        Integer type = getOptionalInteger(command.getPayload(), "type");
        Integer difficulty = getOptionalInteger(command.getPayload(), "difficulty");
        String keyword = getOptionalString(command.getPayload(), "keyword");
        List<String> knowledgePoints = getStringList(command.getPayload(), "knowledgePoints");
        validateQuestionQueryFilters(type, difficulty, knowledgePoints);

        Page<Question> page = new Page<>(current, size);
        QueryWrapper<Question> wrapper = new QueryWrapper<>();
        if (courseId != null) {
            wrapper.eq("course_id", courseId);
        }
        if (type != null) {
            wrapper.eq("type", type);
        }
        if (difficulty != null) {
            wrapper.eq("difficulty", difficulty);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like("stem", keyword.trim());
        }
        if (!knowledgePoints.isEmpty()) {
            for (String knowledgePoint : knowledgePoints) {
                wrapper.like("knowledge_points", knowledgePoint);
            }
        }
        wrapper.orderByDesc("create_time");
        return questionService.page(page, wrapper);
    }

    private Map<String, Object> importQuestionsByExcel(StorageCommand command) {
        MultipartFile file = getMultipartFile(command.getPayload(), "file");
        if (file.isEmpty()) {
            throw ApiBusinessException.badRequest("导入文件不能为空");
        }
        try {
            EasyExcel.read(
                            file.getInputStream(),
                            QuestionExcelDTO.class,
                            new QuestionImportListener(questionService, command.getOperatorId()))
                    .sheet()
                    .doRead();
            return Map.of("imported", true);
        } catch (Exception e) {
            throw ApiBusinessException.unprocessable("导入失败: " + e.getMessage());
        }
    }

    private Map<String, Object> saveExamPaper(StorageCommand command) {
        ExamGenerateReq request = convertPayload(command.getPayload(), ExamGenerateReq.class);
        validateExamAction(command.getAction(), request);
        Long examId = examPaperService.generateExam(request, command.getOperatorId());
        saveRuntimeConfig(examId, request);
        return Map.of("examId", examId);
    }

    private Map<String, Object> deleteExamPaper(StorageCommand command) {
        Long examId = getLong(command.getPayload(), "id");
        ExamPaper paper = loadOwnedPaperOrThrow(examId, command.getOperatorId());
        if (Integer.valueOf(1).equals(paper.getStatus())) {
            throw ApiBusinessException.conflict("已发布试卷不能删除，请先撤回后再处理");
        }
        if (!examPaperService.removeById(examId)) {
            throw ApiBusinessException.unprocessable("试卷删除失败");
        }
        return Map.of("deleted", true);
    }

    private Map<String, Object> updateExamVisibility(StorageCommand command, int status) {
        Long examId = getLong(command.getPayload(), "id");
        ExamPaper paper = loadOwnedPaperOrThrow(examId, command.getOperatorId());
        if (status == 1 && Integer.valueOf(1).equals(paper.getStatus())) {
            throw ApiBusinessException.conflict("试卷已经发布");
        }
        if (status == 2 && !Integer.valueOf(1).equals(paper.getStatus())) {
            throw ApiBusinessException.conflict("仅已发布的试卷才能撤回");
        }
        paper.setStatus(status);
        if (status == 1) {
            ExamGenerateReq req = convertPayload(command.getPayload(), ExamGenerateReq.class);
            if (req.getValidStartTime() == null || req.getValidEndTime() == null) {
                throw ApiBusinessException.badRequest("发布试卷必须提供有效开始时间和结束时间");
            }
            if (!req.getValidStartTime().before(req.getValidEndTime())) {
                throw ApiBusinessException.unprocessable("考试有效开始时间必须早于结束时间");
            }
            examPaperService.getExamPaperForTeacher(examId, command.getOperatorId());
            paper.setValidStartTime(req.getValidStartTime());
            paper.setValidEndTime(req.getValidEndTime());
            updateRuntimeWindow(examId, req);
        }
        if (!examPaperService.updateById(paper)) {
            throw ApiBusinessException.unprocessable("试卷状态更新失败");
        }
        return Map.of("status", status);
    }

    private Page<ExamPaper> queryExamPapers(StorageCommand command) {
        Integer current = getInteger(command.getPayload(), "current", 1);
        Integer size = getInteger(command.getPayload(), "size", 10);
        validatePageParams(current, size);
        Long courseId = getOptionalLong(command.getPayload(), "courseId");

        Page<ExamPaper> page = new Page<>(current, size);
        QueryWrapper<ExamPaper> wrapper = new QueryWrapper<>();
        if (courseId != null) {
            wrapper.eq("course_id", courseId);
        }
        if (command.getOperatorId() != null) {
            wrapper.eq("creator_id", command.getOperatorId());
        }
        wrapper.orderByDesc("create_time");
        return examPaperService.page(page, wrapper);
    }

    private ExamPaperStudentVO previewExamPaper(StorageCommand command) {
        Long examId = getLong(command.getPayload(), "id");
        if ("TEACHER".equalsIgnoreCase(command.getOperatorRole())) {
            return examPaperService.getExamPaperForTeacher(examId, command.getOperatorId());
        }
        return examPaperService.getExamPaperForStudent(examId);
    }

    private ExamStatsVO getExamStats(StorageCommand command) {
        Long examId = getLong(command.getPayload(), "id");
        loadOwnedPaperOrThrow(examId, command.getOperatorId());
        return studentExamRecordService.getExamStats(examId);
    }

    private Map<String, Object> updateExamScoreVisibility(StorageCommand command) {
        Long examId = getLong(command.getPayload(), "id");
        loadOwnedPaperOrThrow(examId, command.getOperatorId());
        ExamRuntimeConfig config = getOrCreateRuntimeConfig(examId);
        config.setScoreVisible(1);
        upsertRuntimeConfig(config);
        return Map.of("scoreVisible", true);
    }

    private Map<String, Object> updateExamAnswerVisibility(StorageCommand command) {
        Long examId = getLong(command.getPayload(), "id");
        loadOwnedPaperOrThrow(examId, command.getOperatorId());
        ExamRuntimeConfig config = getOrCreateRuntimeConfig(examId);
        config.setAnswerVisible(1);
        upsertRuntimeConfig(config);
        return Map.of("answerVisible", true);
    }

    private Map<String, Object> exportExamScores(StorageCommand command) {
        Long examId = getLong(command.getPayload(), "id");
        loadOwnedPaperOrThrow(examId, command.getOperatorId());
        HttpServletResponse response = getHttpServletResponse(command.getPayload(), "response");
        try {
            response.setContentType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            String fileName =
                    URLEncoder.encode("成绩单_试卷_" + examId, StandardCharsets.UTF_8)
                            .replaceAll("\\+", "%20");
            response.setHeader(
                    "Content-disposition",
                    "attachment;filename*=utf-8''" + fileName + ".xlsx");

            QueryWrapper<StudentExamRecord> wrapper = new QueryWrapper<>();
            wrapper.eq("exam_id", examId).eq("status", 1).orderByDesc("total_score");
            List<StudentExamRecord> records = studentExamRecordMapper.selectList(wrapper);

            List<ExamScoreExcelDTO> exportList = new ArrayList<>();
            for (StudentExamRecord record : records) {
                ExamScoreExcelDTO dto = new ExamScoreExcelDTO();
                dto.setStudentId(record.getStudentId());
                dto.setTotalScore(record.getTotalScore());
                dto.setSubmitTime(record.getSubmitTime());
                exportList.add(dto);
            }

            EasyExcel.write(response.getOutputStream(), ExamScoreExcelDTO.class)
                    .sheet("成绩排名")
                    .doWrite(exportList);
            return Map.of("exported", true);
        } catch (Exception e) {
            throw ApiBusinessException.unprocessable("导出Excel失败: " + e.getMessage());
        }
    }

    private void saveRuntimeConfig(Long examId, ExamGenerateReq request) {
        ExamRuntimeConfig config = getOrCreateRuntimeConfig(examId);
        config.setAllowedAttempts(request.getAllowedAttempts() == null ? 1 : request.getAllowedAttempts());
        config.setScoringStrategy(
                request.getScoringStrategy() == null || request.getScoringStrategy().isBlank()
                        ? "AUTO_GRADE"
                        : request.getScoringStrategy());
        upsertRuntimeConfig(config);
    }

    private void updateRuntimeWindow(Long examId, ExamGenerateReq request) {
        ExamRuntimeConfig config = getOrCreateRuntimeConfig(examId);
        if (request.getAllowedAttempts() != null) {
            config.setAllowedAttempts(request.getAllowedAttempts());
        }
        if (request.getScoringStrategy() != null && !request.getScoringStrategy().isBlank()) {
            config.setScoringStrategy(request.getScoringStrategy());
        }
        upsertRuntimeConfig(config);
    }

    private ExamRuntimeConfig getOrCreateRuntimeConfig(Long examId) {
        ExamRuntimeConfig config = examRuntimeConfigMapper.selectById(examId);
        if (config == null) {
            config = new ExamRuntimeConfig();
            config.setExamId(examId);
            config.setAllowedAttempts(1);
            config.setScoreVisible(0);
            config.setAnswerVisible(0);
            config.setScoringStrategy("AUTO_GRADE");
        }
        return config;
    }

    private void upsertRuntimeConfig(ExamRuntimeConfig config) {
        if (examRuntimeConfigMapper.selectById(config.getExamId()) == null) {
            examRuntimeConfigMapper.insert(config);
        } else {
            examRuntimeConfigMapper.updateById(config);
        }
    }

    private <T> T convertPayload(Map<String, Object> payload, Class<T> type) {
        return objectMapper.convertValue(payload, type);
    }

    private MultipartFile getMultipartFile(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof MultipartFile multipartFile) {
            return multipartFile;
        }
        throw ApiBusinessException.badRequest("缺少上传文件参数: " + key);
    }

    private HttpServletResponse getHttpServletResponse(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof HttpServletResponse servletResponse) {
            return servletResponse;
        }
        throw ApiBusinessException.badRequest("缺少响应对象参数: " + key);
    }

    private Long getLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw ApiBusinessException.badRequest("缺少参数: " + key);
        }
        try {
            long result = Long.parseLong(value.toString());
            if (result <= 0) {
                throw ApiBusinessException.badRequest(key + " 必须为正整数");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw ApiBusinessException.badRequest(key + " 必须为正整数");
        }
    }

    private Long getOptionalLong(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        try {
            long result = Long.parseLong(value.toString());
            if (result <= 0) {
                throw ApiBusinessException.badRequest(key + " 必须为正整数");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw ApiBusinessException.badRequest(key + " 必须为正整数");
        }
    }

    private Integer getInteger(Map<String, Object> payload, String key, Integer defaultValue) {
        Object value = payload.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            throw ApiBusinessException.badRequest(key + " 必须为整数");
        }
    }

    private Integer getOptionalInteger(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            throw ApiBusinessException.badRequest(key + " 必须为整数");
        }
    }

    private String getOptionalString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    private List<String> getStringList(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return List.of();
        }
        return objectMapper.convertValue(value, new TypeReference<List<String>>() {});
    }

    private void validatePageParams(Integer current, Integer size) {
        if (current == null || current <= 0) {
            throw ApiBusinessException.badRequest("分页页码必须大于 0");
        }
        if (size == null || size <= 0) {
            throw ApiBusinessException.badRequest("分页大小必须大于 0");
        }
        if (size > 100) {
            throw ApiBusinessException.badRequest("分页大小不能超过 100");
        }
    }

    private void validateQuestionPayload(Question question, boolean requireId) {
        if (question == null) {
            throw ApiBusinessException.badRequest("题目数据不能为空");
        }
        if (requireId && (question.getId() == null || question.getId() <= 0)) {
            throw ApiBusinessException.badRequest("题目 id 必须为正整数");
        }
        if (question.getCourseId() == null || question.getCourseId() <= 0) {
            throw ApiBusinessException.badRequest("课程 id 必须为正整数");
        }
        if (question.getType() == null || (question.getType() != 1 && question.getType() != 2)) {
            throw ApiBusinessException.badRequest("题型仅支持 1(单选) 或 2(是非)");
        }
        if (question.getStem() == null || question.getStem().isBlank()) {
            throw ApiBusinessException.badRequest("题干不能为空");
        }
        if (question.getAnswer() == null || question.getAnswer().isBlank()) {
            throw ApiBusinessException.badRequest("标准答案不能为空");
        }
        if (question.getDifficulty() == null
                || question.getDifficulty() < 1
                || question.getDifficulty() > 3) {
            throw ApiBusinessException.badRequest("难度必须在 1 到 3 之间");
        }
        if (question.getType() == 1) {
            if (question.getOptions() == null || question.getOptions().size() < 2) {
                throw ApiBusinessException.badRequest("单选题至少需要两个选项");
            }
        }
        if (question.getOptions() != null
                && question.getOptions().stream().anyMatch(option -> option == null || option.isBlank())) {
            throw ApiBusinessException.badRequest("题目选项不能为空字符串");
        }
        if (question.getType() == 2
                && (question.getOptions() == null || question.getOptions().isEmpty())) {
            question.setOptions(List.of("True", "False"));
        }
        if (question.getKnowledgePoints() != null
                && question.getKnowledgePoints().stream()
                        .anyMatch(point -> point == null || point.isBlank())) {
            throw ApiBusinessException.badRequest("知识点不能为空字符串");
        }
    }

    private ExamPaper loadOwnedPaperOrThrow(Long examId, Long operatorId) {
        ExamPaper paper = examPaperService.getById(examId);
        if (paper == null) {
            throw ApiBusinessException.notFound("试卷不存在或已删除");
        }
        if (operatorId == null || !Objects.equals(paper.getCreatorId(), operatorId)) {
            throw ApiBusinessException.forbidden("无权操作其他教师创建的试卷");
        }
        return paper;
    }

    private void validateQuestionOwnership(Question question, Long operatorId, String actionLabel) {
        if (question.getCreatorId() != null
                && operatorId != null
                && !Objects.equals(question.getCreatorId(), operatorId)) {
            throw ApiBusinessException.forbidden("无权" + actionLabel + "其他教师创建的题目");
        }
    }

    private Boolean getBoolean(Map<String, Object> payload, String key, Boolean defaultValue) {
        Object value = payload.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        String text = value.toString().trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw ApiBusinessException.badRequest(key + " 必须为布尔值");
    }

    private void validateQuestionQueryFilters(
            Integer type, Integer difficulty, List<String> knowledgePoints) {
        if (type != null && type != 1 && type != 2) {
            throw ApiBusinessException.badRequest("题型筛选仅支持 1(单选) 或 2(是非)");
        }
        if (difficulty != null && (difficulty < 1 || difficulty > 3)) {
            throw ApiBusinessException.badRequest("难度筛选必须在 1 到 3 之间");
        }
        if (knowledgePoints.stream().anyMatch(point -> point == null || point.isBlank())) {
            throw ApiBusinessException.badRequest("知识点筛选不能为空字符串");
        }
    }

    private void validateExamAction(String action, ExamGenerateReq request) {
        if (request == null) {
            throw ApiBusinessException.badRequest("试卷请求不能为空");
        }
        switch (action) {
            case "create_exam_paper" -> {
                if (request.getId() != null) {
                    throw ApiBusinessException.badRequest("创建试卷时不能传入 id");
                }
            }
            case "update_exam_paper" -> {
                if (request.getId() == null) {
                    throw ApiBusinessException.badRequest("更新试卷必须传入 id");
                }
                if (request.getId() <= 0) {
                    throw ApiBusinessException.badRequest("试卷 id 必须为正整数");
                }
            }
            case "generate_exam_paper" -> {
                if (request.getId() != null) {
                    throw ApiBusinessException.badRequest("自动组卷创建试卷时不能传入 id");
                }
            }
            default -> {
                // no-op
            }
        }
    }
}
