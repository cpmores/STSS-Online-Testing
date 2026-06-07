package com.stss.online_testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stss.online_testing.common.Result;
import com.stss.online_testing.core.api.ApiActionProtocol;
import com.stss.online_testing.core.api.ApiServerController;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnlineTestingApplicationTests {

    private static final Long TEST_TEACHER_ID = 99001L;
    private static final Long TEST_STUDENT_ID = 99002L;
    private static final Long TEST_COURSE_ID = 88001L;
    private static final String TEST_QUESTION_STEM = "集成测试题目";
    private static final String TEST_IMPORT_QUESTION_STEM = "Excel导入知识点测试题";
    private static final String TEST_PAPER_TITLE = "集成测试试卷";
    private static final List<String> TEST_PAPER_TITLES =
            List.of(
                    TEST_PAPER_TITLE,
                    "删除题目后组卷",
                    "非法题目ID组卷",
                    "草稿预览试卷",
                    "草稿删除试卷",
                    "已发布删除试卷",
                    "手工分值更新试卷",
                    "自动组卷分值试卷",
                    "自动组卷非法分值试卷");

    @Autowired
    private ApiServerController apiServerController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanAfterEach() {
        cleanTestData();
    }

    @Test
    void contextLoads() {
    }

    @Test
    void unifiedActionFlowPersistsToMysql() {
        cleanTestData();

        Long courseId = TEST_COURSE_ID;
        Long questionId = addQuestion(courseId);
        Long examId = createAndPublishPaper(courseId, questionId);

        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM question WHERE id = ? AND course_id = ?",
                        questionId,
                        courseId));
        assertEquals(1, count("SELECT COUNT(*) FROM exam_paper WHERE id = ? AND status = 1", examId));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM exam_paper_question WHERE paper_id = ? AND question_id = ?",
                        examId,
                        questionId));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM exam_runtime_config WHERE exam_id = ? AND allowed_attempts = 2",
                        examId));

        Long recordId = beginExam(examId);
        saveExamProgress(examId, recordId, questionId);
        submitExam(examId, recordId, questionId);
        reviewExamRecord(recordId);
        listMyExamRecords();

        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM student_exam_record "
                                + "WHERE id = ? AND exam_id = ? AND student_id = ? AND total_score = 10 AND status = 1",
                        recordId,
                        examId,
                        TEST_STUDENT_ID));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM student_exam_answer "
                                + "WHERE record_id = ? AND question_id = ? AND is_correct = 1 AND score = 10",
                        recordId,
                        questionId));
    }

    @Test
    void invalidActionReturnsFormattedErrorWithoutAuthorizationHeader() {
        cleanTestData();

        ApiActionProtocol.Request request = new ApiActionProtocol.Request();
        request.setAction("unknown_action");
        request.setData(Map.of("teacherId", TEST_TEACHER_ID, "sample", "value"));

        try {
            mockMvc.perform(
                            post("/api/ot/v1/actions")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("未注册的 action: unknown_action"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    @Test
    void loggerMirrorPersistsEvenWhenGrpcLoggerIsUnavailable() throws Exception {
        cleanTestData();

        ApiActionProtocol.Request request = new ApiActionProtocol.Request();
        request.setAction("unknown_action");
        request.setData(Map.of("teacherId", TEST_TEACHER_ID));

        Result<Object> response = dispatchViaHttp(request);
        assertEquals(400, response.getCode());

        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM action_log "
                                + "WHERE user_id = ? AND status_code = 400 "
                                + "AND grpc_delivered = 0 "
                                + "AND JSON_UNQUOTE(JSON_EXTRACT(string_fields, '$.action')) = 'unknown_action'",
                        String.valueOf(TEST_TEACHER_ID)));
    }

    @Test
    void importQuestionExcelWithVariantHeadersPersistsKnowledgePoints() throws Exception {
        cleanTestData();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        List<List<String>> head = List.of(
                List.of("课程ID"),
                List.of("题型（1单选,题型）"),
                List.of("题干"),
                List.of("选项(JSON数组)"),
                List.of("标准答案"),
                List.of("难度（1-3）"),
                List.of("知识点(JSON数组)"));
        List<List<Object>> rows = List.of(
                List.of(
                        TEST_COURSE_ID,
                        1,
                        TEST_IMPORT_QUESTION_STEM,
                        "[\"A. 正确\",\"B. 错误\"]",
                        "A",
                        1,
                        "[\"数据结构\"]"));
        EasyExcel.write(outputStream).head(head).sheet("Sheet1").doWrite(rows);

        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "questions.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        outputStream.toByteArray());

        mockMvc.perform(
                        multipart("/api/ot/v1/actions/question-bank/import")
                                .file(file)
                                .param("teacherId", String.valueOf(TEST_TEACHER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.imported").value(true));

        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM question "
                                + "WHERE creator_id = ? AND stem = ? "
                                + "AND JSON_SEARCH(knowledge_points, 'one', '数据结构') IS NOT NULL",
                        TEST_TEACHER_ID,
                        TEST_IMPORT_QUESTION_STEM));
    }

    @Test
    void deletedQuestionCannotBeFetchedOrUsedForPaperGeneration() throws Exception {
        cleanTestData();

        Long questionId = addQuestion(TEST_COURSE_ID);
        ApiActionProtocol.Request deleteRequest = new ApiActionProtocol.Request();
        deleteRequest.setAction("delete_a_question");
        deleteRequest.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "id", questionId,
                        "force", true));

        Result<Object> deleteResponse = apiServerController.dispatch(deleteRequest);
        assertEquals(200, deleteResponse.getCode());

        ApiActionProtocol.Request getRequest = new ApiActionProtocol.Request();
        getRequest.setAction("get_a_question");
        getRequest.setData(Map.of("teacherId", TEST_TEACHER_ID, "id", questionId));
        Result<Object> getResponse = dispatchViaHttp(getRequest);
        assertEquals(404, getResponse.getCode());
        assertEquals("题目不存在或已删除", getResponse.getMessage());

        ApiActionProtocol.Request createRequest = new ApiActionProtocol.Request();
        createRequest.setAction("create_exam_paper");
        createRequest.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "courseId", TEST_COURSE_ID,
                        "title", "删除题目后组卷",
                        "totalScore", 10,
                        "durationMins", 30,
                        "passScore", 6,
                        "allowedAttempts", 1,
                        "generateMode", "manual",
                        "questionIds", List.of(questionId),
                        "questionScores", List.of(10)));
        Result<Object> createResponse = dispatchViaHttp(createRequest);
        assertEquals(404, createResponse.getCode());
        assertTrue(createResponse.getMessage().contains("不存在或已删除"));
    }

    @Test
    void createExamPaperRejectsNonexistentQuestionId() throws Exception {
        cleanTestData();

        ApiActionProtocol.Request createRequest = new ApiActionProtocol.Request();
        createRequest.setAction("create_exam_paper");
        createRequest.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "courseId", TEST_COURSE_ID,
                        "title", "非法题目ID组卷",
                        "totalScore", 20,
                        "durationMins", 30,
                        "passScore", 12,
                        "allowedAttempts", 1,
                        "generateMode", "manual",
                        "questionIds", List.of(20000L),
                        "questionScores", List.of(20)));

        Result<Object> createResponse = dispatchViaHttp(createRequest);
        assertEquals(404, createResponse.getCode());
        assertTrue(createResponse.getMessage().contains("以下题目不存在或已删除"));
    }

    @Test
    void manualUpdateUsesExplicitQuestionScoresWithoutDivisibilityConstraint() throws Exception {
        cleanTestData();

        Long questionId1 = addQuestion(TEST_COURSE_ID, 1, TEST_QUESTION_STEM + "-manual-1");
        Long questionId2 = addQuestion(TEST_COURSE_ID, 1, TEST_QUESTION_STEM + "-manual-2");
        Long questionId3 = addQuestion(TEST_COURSE_ID, 2, TEST_QUESTION_STEM + "-manual-3");

        ApiActionProtocol.Request createRequest = new ApiActionProtocol.Request();
        createRequest.setAction("create_exam_paper");
        createRequest.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "courseId", TEST_COURSE_ID,
                        "title", "手工分值更新试卷",
                        "totalScore", 20,
                        "durationMins", 30,
                        "passScore", 12,
                        "allowedAttempts", 1,
                        "generateMode", "manual",
                        "questionIds", List.of(questionId1, questionId2),
                        "questionScores", List.of(10, 10)));
        Result<Object> createResponse = apiServerController.dispatch(createRequest);
        assertEquals(200, createResponse.getCode());
        Long examId = Long.valueOf(((Map<?, ?>) createResponse.getData()).get("examId").toString());

        ApiActionProtocol.Request updateRequest = new ApiActionProtocol.Request();
        updateRequest.setAction("update_exam_paper");
        updateRequest.setData(
                Map.ofEntries(
                        Map.entry("teacherId", TEST_TEACHER_ID),
                        Map.entry("id", examId),
                        Map.entry("courseId", TEST_COURSE_ID),
                        Map.entry("title", "手工分值更新试卷"),
                        Map.entry("totalScore", 100),
                        Map.entry("durationMins", 100),
                        Map.entry("passScore", 60),
                        Map.entry("allowedAttempts", 2),
                        Map.entry("generateMode", "manual"),
                        Map.entry("questionIds", List.of(questionId1, questionId2, questionId3)),
                        Map.entry("questionScores", List.of(20, 30, 50))));

        Result<Object> updateResponse = dispatchViaHttp(updateRequest);
        assertEquals(200, updateResponse.getCode());

        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM exam_paper_question "
                                + "WHERE paper_id = ? AND question_id = ? AND score = 20",
                        examId,
                        questionId1));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM exam_paper_question "
                                + "WHERE paper_id = ? AND question_id = ? AND score = 30",
                        examId,
                        questionId2));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM exam_paper_question "
                                + "WHERE paper_id = ? AND question_id = ? AND score = 50",
                        examId,
                        questionId3));
    }

    @Test
    void autoGenerationUsesPerTypeCountsAndScores() throws Exception {
        cleanTestData();

        addQuestion(TEST_COURSE_ID, 1, TEST_QUESTION_STEM + "-auto-single-1");
        addQuestion(TEST_COURSE_ID, 1, TEST_QUESTION_STEM + "-auto-single-2");
        addQuestion(TEST_COURSE_ID, 1, TEST_QUESTION_STEM + "-auto-single-3");
        addQuestion(TEST_COURSE_ID, 2, TEST_QUESTION_STEM + "-auto-tf-1");
        addQuestion(TEST_COURSE_ID, 2, TEST_QUESTION_STEM + "-auto-tf-2");
        addQuestion(TEST_COURSE_ID, 2, TEST_QUESTION_STEM + "-auto-tf-3");
        addQuestion(TEST_COURSE_ID, 2, TEST_QUESTION_STEM + "-auto-tf-4");

        ApiActionProtocol.Request createRequest = new ApiActionProtocol.Request();
        createRequest.setAction("generate_exam_paper");
        createRequest.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "courseId", TEST_COURSE_ID,
                        "title", "自动组卷分值试卷",
                        "totalScore", 70,
                        "durationMins", 60,
                        "passScore", 42,
                        "allowedAttempts", 1,
                        "generateMode", "auto",
                        "autoRules", Map.of(
                                "singleChoiceCount", 2,
                                "trueFalseCount", 3,
                                "singleChoiceScore", 20,
                                "trueFalseScore", 10,
                                "targetDifficulty", 1,
                                "knowledgePoints", List.of("integration-test"))));

        Result<Object> createResponse = dispatchViaHttp(createRequest);
        assertEquals(200, createResponse.getCode());
        Long examId = Long.valueOf(((Map<?, ?>) createResponse.getData()).get("examId").toString());

        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM exam_paper_question epq "
                                + "JOIN question q ON epq.question_id = q.id "
                                + "WHERE epq.paper_id = ? AND q.type = 1 AND epq.score = 20",
                        examId));
        assertEquals(
                3,
                count(
                        "SELECT COUNT(*) FROM exam_paper_question epq "
                                + "JOIN question q ON epq.question_id = q.id "
                                + "WHERE epq.paper_id = ? AND q.type = 2 AND epq.score = 10",
                        examId));
        assertEquals(
                70,
                count("SELECT COALESCE(SUM(score), 0) FROM exam_paper_question WHERE paper_id = ?", examId));
    }

    @Test
    void autoGenerationRejectsMismatchedCountsAndScores() throws Exception {
        cleanTestData();

        addQuestion(TEST_COURSE_ID, 1, TEST_QUESTION_STEM + "-auto-invalid-single");
        addQuestion(TEST_COURSE_ID, 2, TEST_QUESTION_STEM + "-auto-invalid-tf");

        ApiActionProtocol.Request createRequest = new ApiActionProtocol.Request();
        createRequest.setAction("generate_exam_paper");
        createRequest.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "courseId", TEST_COURSE_ID,
                        "title", "自动组卷非法分值试卷",
                        "totalScore", 100,
                        "durationMins", 60,
                        "passScore", 60,
                        "allowedAttempts", 1,
                        "generateMode", "auto",
                        "autoRules", Map.of(
                                "singleChoiceCount", 1,
                                "trueFalseCount", 1,
                                "singleChoiceScore", 30,
                                "trueFalseScore", 20)));

        Result<Object> createResponse = dispatchViaHttp(createRequest);
        assertEquals(422, createResponse.getCode());
        assertTrue(createResponse.getMessage().contains("自动组卷分值配置不合法"));
    }

    @Test
    void teacherCanPreviewUnpublishedPaper() {
        cleanTestData();

        Long questionId = addQuestion(TEST_COURSE_ID);
        ApiActionProtocol.Request createRequest = new ApiActionProtocol.Request();
        createRequest.setAction("create_exam_paper");
        createRequest.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "courseId", TEST_COURSE_ID,
                        "title", "草稿预览试卷",
                        "totalScore", 10,
                        "durationMins", 30,
                        "passScore", 6,
                        "allowedAttempts", 1,
                        "generateMode", "manual",
                        "questionIds", List.of(questionId),
                        "questionScores", List.of(10)));
        Result<Object> createResponse = apiServerController.dispatch(createRequest);
        assertEquals(200, createResponse.getCode());
        Long examId = Long.valueOf(((Map<?, ?>) createResponse.getData()).get("examId").toString());

        ApiActionProtocol.Request previewRequest = new ApiActionProtocol.Request();
        previewRequest.setAction("preview_exam_paper");
        previewRequest.setData(Map.of("teacherId", TEST_TEACHER_ID, "id", examId));
        Result<Object> previewResponse = apiServerController.dispatch(previewRequest);
        assertEquals(200, previewResponse.getCode());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) objectMapper.convertValue(
                previewResponse.getData(), Map.class);
        assertEquals(examId.intValue(), ((Number) data.get("examId")).intValue());
        assertEquals("草稿预览试卷", data.get("title"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions = (List<Map<String, Object>>) data.get("questions");
        assertFalse(questions.isEmpty());
    }

    @Test
    void submitExamRejectsQuestionOutsidePaper() throws Exception {
        cleanTestData();

        Long questionId = addQuestion(TEST_COURSE_ID);
        Long examId = createAndPublishPaper(TEST_COURSE_ID, questionId);
        Long recordId = beginExam(examId);

        ApiActionProtocol.Request submitRequest = new ApiActionProtocol.Request();
        submitRequest.setAction("submit_exam_answers");
        submitRequest.setData(
                Map.of(
                        "studentId", TEST_STUDENT_ID,
                        "examId", examId,
                        "recordId", recordId,
                        "answers", List.of(Map.of("questionId", 999999L, "studentAnswer", "A"))));

        Result<Object> submitResponse = dispatchViaHttp(submitRequest);
        assertEquals(422, submitResponse.getCode());
        assertTrue(submitResponse.getMessage().contains("不属于当前试卷"));
    }

    @Test
    void draftExamPaperCanBeDeleted() throws Exception {
        cleanTestData();

        Long questionId = addQuestion(TEST_COURSE_ID);
        ApiActionProtocol.Request createRequest = new ApiActionProtocol.Request();
        createRequest.setAction("create_exam_paper");
        createRequest.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "courseId", TEST_COURSE_ID,
                        "title", "草稿删除试卷",
                        "totalScore", 10,
                        "durationMins", 30,
                        "passScore", 6,
                        "allowedAttempts", 1,
                        "generateMode", "manual",
                        "questionIds", List.of(questionId),
                        "questionScores", List.of(10)));
        Result<Object> createResponse = apiServerController.dispatch(createRequest);
        Long examId = Long.valueOf(((Map<?, ?>) createResponse.getData()).get("examId").toString());

        ApiActionProtocol.Request deleteRequest = new ApiActionProtocol.Request();
        deleteRequest.setAction("delete_exam_paper");
        deleteRequest.setData(Map.of("teacherId", TEST_TEACHER_ID, "id", examId));
        Result<Object> deleteResponse = dispatchViaHttp(deleteRequest);
        assertEquals(200, deleteResponse.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) deleteResponse.getData();
        assertEquals(Boolean.TRUE, data.get("deleted"));

        assertEquals(0, count("SELECT COUNT(*) FROM exam_paper WHERE id = ? AND is_deleted = 0", examId));
    }

    @Test
    void publishedExamPaperCannotBeDeleted() throws Exception {
        cleanTestData();

        Long questionId = addQuestion(TEST_COURSE_ID);
        ApiActionProtocol.Request createRequest = new ApiActionProtocol.Request();
        createRequest.setAction("create_exam_paper");
        createRequest.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "courseId", TEST_COURSE_ID,
                        "title", "已发布删除试卷",
                        "totalScore", 10,
                        "durationMins", 30,
                        "passScore", 6,
                        "allowedAttempts", 1,
                        "generateMode", "manual",
                        "questionIds", List.of(questionId),
                        "questionScores", List.of(10)));
        Result<Object> createResponse = apiServerController.dispatch(createRequest);
        Long examId = Long.valueOf(((Map<?, ?>) createResponse.getData()).get("examId").toString());

        ApiActionProtocol.Request publishRequest = new ApiActionProtocol.Request();
        publishRequest.setAction("publish_exam_paper");
        publishRequest.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "id", examId,
                        "validStartTime", "2026-01-01T00:00:00.000+08:00",
                        "validEndTime", "2026-12-31T23:59:59.000+08:00",
                        "allowedAttempts", 1));
        Result<Object> publishResponse = apiServerController.dispatch(publishRequest);
        assertEquals(200, publishResponse.getCode());

        ApiActionProtocol.Request deleteRequest = new ApiActionProtocol.Request();
        deleteRequest.setAction("delete_exam_paper");
        deleteRequest.setData(Map.of("teacherId", TEST_TEACHER_ID, "id", examId));
        Result<Object> deleteResponse = dispatchViaHttp(deleteRequest);
        assertEquals(409, deleteResponse.getCode());
        assertEquals("已发布试卷不能删除，请先撤回后再处理", deleteResponse.getMessage());
    }

    private Long addQuestion(Long courseId) {
        return addQuestion(courseId, 1, TEST_QUESTION_STEM);
    }

    private Long addQuestion(Long courseId, Integer type, String stem) {
        ApiActionProtocol.Request request = new ApiActionProtocol.Request();
        request.setAction("add_a_question");
        request.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "courseId", courseId,
                        "type", type,
                        "stem", stem,
                        "options", type == 1
                                ? List.of("A. 正确", "B. 错误", "C. 干扰项")
                                : List.of("True", "False"),
                        "answer", type == 1 ? "A" : "True",
                        "difficulty", 1,
                        "knowledgePoints", List.of("integration-test")));

        Result<Object> response = apiServerController.dispatch(request);
        assertEquals(200, response.getCode());
        return Long.valueOf(((Map<?, ?>) response.getData()).get("id").toString());
    }

    private Long createAndPublishPaper(Long courseId, Long questionId) {
        ApiActionProtocol.Request createRequest = new ApiActionProtocol.Request();
        createRequest.setAction("create_exam_paper");
        createRequest.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "courseId", courseId,
                        "title", TEST_PAPER_TITLE,
                        "totalScore", 10,
                        "durationMins", 30,
                        "passScore", 6,
                        "allowedAttempts", 2,
                        "generateMode", "manual",
                        "questionIds", List.of(questionId),
                        "questionScores", List.of(10)));

        Result<Object> createResponse = apiServerController.dispatch(createRequest);
        assertEquals(200, createResponse.getCode());
        Long examId = Long.valueOf(((Map<?, ?>) createResponse.getData()).get("examId").toString());

        ApiActionProtocol.Request publishRequest = new ApiActionProtocol.Request();
        publishRequest.setAction("publish_exam_paper");
        publishRequest.setData(
                Map.of(
                        "teacherId", TEST_TEACHER_ID,
                        "id", examId,
                        "validStartTime", "2026-01-01T00:00:00.000+08:00",
                        "validEndTime", "2026-12-31T23:59:59.000+08:00",
                        "allowedAttempts", 2));
        Result<Object> publishResponse = apiServerController.dispatch(publishRequest);
        assertEquals(200, publishResponse.getCode());
        return examId;
    }

    private Long beginExam(Long examId) {
        ApiActionProtocol.Request beginRequest = new ApiActionProtocol.Request();
        beginRequest.setAction("begin_an_exam");
        beginRequest.setData(Map.of("id", examId, "studentId", TEST_STUDENT_ID));

        Result<Object> beginResponse = apiServerController.dispatch(beginRequest);
        assertEquals(200, beginResponse.getCode());
        Object recordId = ((Map<?, ?>) beginResponse.getData()).get("recordId");
        assertNotNull(recordId);
        return Long.valueOf(recordId.toString());
    }

    private void saveExamProgress(Long examId, Long recordId, Long questionId) {
        ApiActionProtocol.Request saveRequest = new ApiActionProtocol.Request();
        saveRequest.setAction("save_exam_progress");
        saveRequest.setData(
                Map.of(
                        "studentId", TEST_STUDENT_ID,
                        "examId", examId,
                        "recordId", recordId,
                        "answers", List.of(Map.of("questionId", questionId, "studentAnswer", "A"))));

        Result<Object> saveResponse = apiServerController.dispatch(saveRequest);
        assertEquals(200, saveResponse.getCode());
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>) saveResponse.getData()).get("saved")));
    }

    private void submitExam(Long examId, Long recordId, Long questionId) {
        ApiActionProtocol.Request submitRequest = new ApiActionProtocol.Request();
        submitRequest.setAction("submit_exam_answers");
        submitRequest.setData(
                Map.of(
                        "studentId", TEST_STUDENT_ID,
                        "examId", examId,
                        "recordId", recordId,
                        "answers", List.of(Map.of("questionId", questionId, "studentAnswer", "A"))));

        Result<Object> submitResponse = apiServerController.dispatch(submitRequest);
        assertEquals(200, submitResponse.getCode());
    }

    private void reviewExamRecord(Long recordId) {
        ApiActionProtocol.Request reviewRequest = new ApiActionProtocol.Request();
        reviewRequest.setAction("get_exam_record_review");
        reviewRequest.setData(Map.of("studentId", TEST_STUDENT_ID, "recordId", recordId));

        Result<Object> reviewResponse = apiServerController.dispatch(reviewRequest);
        assertEquals(200, reviewResponse.getCode());
        assertEquals(recordId, Long.valueOf(((Map<?, ?>) reviewResponse.getData()).get("recordId").toString()));
    }

    @SuppressWarnings("unchecked")
    private void listMyExamRecords() {
        ApiActionProtocol.Request listRequest = new ApiActionProtocol.Request();
        listRequest.setAction("list_my_exam_records");
        listRequest.setData(Map.of("studentId", TEST_STUDENT_ID, "current", 1, "size", 10));

        Result<Object> listResponse = apiServerController.dispatch(listRequest);
        assertEquals(200, listResponse.getCode());
        Page<Object> page = (Page<Object>) listResponse.getData();
        assertTrue(page.getRecords().size() >= 1);
    }

    private Integer count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private Result<Object> dispatchViaHttp(ApiActionProtocol.Request request) throws Exception {
        byte[] responseBody =
                mockMvc.perform(
                                post("/api/ot/v1/actions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(request)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        return objectMapper.readValue(responseBody, new TypeReference<Result<Object>>() {});
    }

    private void cleanTestData() {
        jdbcTemplate.update(
                "DELETE FROM student_exam_answer WHERE record_id IN ("
                        + "SELECT id FROM student_exam_record WHERE student_id = ? OR course_id = ?)",
                TEST_STUDENT_ID,
                TEST_COURSE_ID);
        jdbcTemplate.update(
                "DELETE FROM student_exam_record WHERE student_id = ? OR course_id = ?",
                TEST_STUDENT_ID,
                TEST_COURSE_ID);
        jdbcTemplate.update(
                "DELETE FROM exam_runtime_config WHERE exam_id IN ("
                        + "SELECT id FROM exam_paper WHERE creator_id = ? OR course_id = ?)",
                TEST_TEACHER_ID,
                TEST_COURSE_ID);
        jdbcTemplate.update(
                "DELETE FROM exam_paper_question WHERE paper_id IN ("
                        + "SELECT id FROM exam_paper WHERE creator_id = ? OR course_id = ?)",
                TEST_TEACHER_ID,
                TEST_COURSE_ID);
        jdbcTemplate.update(
                "DELETE FROM exam_paper WHERE creator_id = ? OR course_id = ? OR title IN (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                TEST_TEACHER_ID,
                TEST_COURSE_ID,
                TEST_PAPER_TITLES.get(0),
                TEST_PAPER_TITLES.get(1),
                TEST_PAPER_TITLES.get(2),
                TEST_PAPER_TITLES.get(3),
                TEST_PAPER_TITLES.get(4),
                TEST_PAPER_TITLES.get(5),
                TEST_PAPER_TITLES.get(6),
                TEST_PAPER_TITLES.get(7),
                TEST_PAPER_TITLES.get(8));
        jdbcTemplate.update(
                "DELETE FROM question WHERE creator_id = ? OR course_id = ? OR stem = ?",
                TEST_TEACHER_ID,
                TEST_COURSE_ID,
                TEST_QUESTION_STEM);
        jdbcTemplate.update(
                "DELETE FROM question WHERE creator_id = ? OR course_id = ? OR stem = ?",
                TEST_TEACHER_ID,
                TEST_COURSE_ID,
                TEST_IMPORT_QUESTION_STEM);
        jdbcTemplate.update(
                "DELETE FROM action_log "
                        + "WHERE user_id IN (?, ?) "
                        + "OR JSON_UNQUOTE(JSON_EXTRACT(string_fields, '$.action')) IN "
                        + "('unknown_action','delete_a_question','get_a_question','create_exam_paper',"
                        + "'update_exam_paper','generate_exam_paper','preview_exam_paper',"
                        + "'submit_exam_answers','delete_exam_paper','publish_exam_paper')",
                String.valueOf(TEST_TEACHER_ID),
                String.valueOf(TEST_STUDENT_ID));
    }
}
