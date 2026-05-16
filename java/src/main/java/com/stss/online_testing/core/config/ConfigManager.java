package com.stss.online_testing.core.config;

import com.stss.online_testing.core.api.ApiActionProtocol;
import com.stss.online_testing.core.logger.ActionLogger;
import com.stss.online_testing.core.proctor.ProctorFacade;
import com.stss.online_testing.core.storage.StorageCommand;
import com.stss.online_testing.core.storage.StorageFacade;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ConfigManager {

    private final StorageFacade storageFacade;
    private final ProctorFacade proctorFacade;
    private final ActionLogger actionLogger;

    public ConfigManager(
            StorageFacade storageFacade, ProctorFacade proctorFacade, ActionLogger actionLogger) {
        this.storageFacade = storageFacade;
        this.proctorFacade = proctorFacade;
        this.actionLogger = actionLogger;
    }

    public ApiActionProtocol.DispatchResult dispatch(ApiActionProtocol.Request request) {
        if (request == null || request.getAction() == null || request.getAction().isBlank()) {
            throw new IllegalArgumentException("action 不能为空");
        }

        String action = request.getAction().trim();
        Map<String, Object> payload =
                request.getData() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(request.getData());
        DispatchContext context = resolveContext(action, payload);

        try {
            ApiActionProtocol.DispatchResult result = switch (action) {
                case "add_a_question" -> teacherResponse("题目添加成功", action, context);
                case "update_a_question" -> teacherResponse("题目更新成功", action, context);
                case "delete_a_question" -> teacherResponse("题目删除处理完成", action, context);
                case "get_a_question" -> teacherResponse("题目信息获取成功", action, context);
                case "query_question_bank" -> teacherResponse("题库查询成功", action, context);
                case "create_exam_paper" -> teacherResponse("试卷创建成功", action, context);
                case "update_exam_paper" -> teacherResponse("试卷更新成功", action, context);
                case "generate_exam_paper" -> teacherResponse("试卷生成成功", action, context);
                case "delete_exam_paper" -> teacherResponse("试卷删除成功", action, context);
                case "publish_exam_paper" -> teacherResponse("试卷发布成功", action, context);
                case "withdraw_exam_paper" -> teacherResponse("试卷撤回成功", action, context);
                case "query_exam_papers" -> teacherResponse("试卷查询成功", action, context);
                case "preview_exam_paper" ->
                        teacherResponse("试卷预览成功", "preview_exam_paper", context);
                case "get_exam_paper_for_student" ->
                        response("试卷预览成功", storage("preview_exam_paper", context));
                case "get_exam_stats" -> teacherResponse("考试统计获取成功", action, context);
                case "open_exam_score" -> teacherResponse("考试成绩开放成功", action, context);
                case "open_exam_answer" -> teacherResponse("考试答案开放成功", action, context);
                case "begin_an_exam" -> studentResponse("考试会话创建成功", action, context);
                case "save_exam_progress" -> studentResponse("答题进度保存成功", action, context);
                case "submit_exam_answers" -> studentResponse("交卷成功", action, context);
                case "get_exam_record_review" -> response("作答记录获取成功", proctor(action, context));
                case "list_my_exam_records" -> studentResponse("考试记录获取成功", action, context);
                default -> throw new IllegalArgumentException("未注册的 action: " + action);
            };
            actionLogger.logSuccess(
                    action,
                    context.operatorId(),
                    context.operatorRole(),
                    payload,
                    result.getData(),
                    result.getMessage());
            return result;
        } catch (Exception exception) {
            actionLogger.logFailure(action, context.operatorId(), context.operatorRole(), payload, exception);
            throw exception;
        }
    }

    public ApiActionProtocol.DispatchResult importQuestions(MultipartFile file, Long teacherId) {
        if (teacherId == null) {
            throw new IllegalArgumentException("导入题库缺少 teacherId");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file", file);
        DispatchContext context = teacherContext(payload, teacherId, true);
        try {
            ApiActionProtocol.DispatchResult result =
                    response("题库导入成功", storage("import_questions_by_excel", context));
            actionLogger.logSuccess(
                    "import_questions_by_excel",
                    context.operatorId(),
                    context.operatorRole(),
                    Map.of("teacherId", teacherId, "fileName", file.getOriginalFilename()),
                    result.getData(),
                    result.getMessage());
            return result;
        } catch (Exception exception) {
            actionLogger.logFailure(
                    "import_questions_by_excel",
                    context.operatorId(),
                    context.operatorRole(),
                    Map.of("teacherId", teacherId, "fileName", file.getOriginalFilename()),
                    exception);
            throw exception;
        }
    }

    public void exportExamScores(Map<String, Object> request, HttpServletResponse response) {
        Map<String, Object> payload = request == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request);
        DispatchContext context = teacherContext(payload, extractLong(payload, "teacherId", true), true);
        Long examId = requirePayloadLong(context.businessPayload(), "id");
        context.businessPayload().put("response", response);
        try {
            storage("export_exam_scores", context);
            actionLogger.logSuccess(
                    "export_exam_scores",
                    context.operatorId(),
                    context.operatorRole(),
                    payload,
                    Map.of("exported", true),
                    "成绩导出成功");
        } catch (Exception exception) {
            actionLogger.logFailure("export_exam_scores", context.operatorId(), context.operatorRole(), payload, exception);
            throw exception;
        }
    }

    private ApiActionProtocol.DispatchResult response(String message, Object data) {
        return new ApiActionProtocol.DispatchResult(message, data);
    }

    private ApiActionProtocol.DispatchResult teacherResponse(
            String message, String action, DispatchContext context) {
        if (!"TEACHER".equals(context.operatorRole())) {
            throw new IllegalArgumentException("该操作需要在 body.data 中提供 teacherId");
        }
        return response(message, storage(action, context));
    }

    private ApiActionProtocol.DispatchResult studentResponse(
            String message, String action, DispatchContext context) {
        if (!"STUDENT".equals(context.operatorRole())) {
            throw new IllegalArgumentException("该操作需要在 body.data 中提供 studentId");
        }
        return response(message, proctor(action, context));
    }

    private Object storage(String action, DispatchContext context) {
        StorageCommand command = new StorageCommand();
        command.setAction(action);
        command.setPayload(context.businessPayload());
        command.setOperatorId(context.operatorId());
        command.setOperatorRole(context.operatorRole());
        return storageFacade.execute(command);
    }

    private Object proctor(String action, DispatchContext context) {
        StorageCommand command = new StorageCommand();
        command.setAction(action);
        command.setPayload(context.businessPayload());
        command.setOperatorId(context.operatorId());
        command.setOperatorRole(context.operatorRole());
        return proctorFacade.execute(command);
    }

    private DispatchContext resolveContext(String action, Map<String, Object> payload) {
        return switch (action) {
            case "add_a_question",
                    "update_a_question",
                    "delete_a_question",
                    "get_a_question",
                    "query_question_bank",
                    "create_exam_paper",
                    "update_exam_paper",
                    "generate_exam_paper",
                    "delete_exam_paper",
                    "publish_exam_paper",
                    "withdraw_exam_paper",
                    "query_exam_papers",
                    "preview_exam_paper",
                    "get_exam_stats",
                    "open_exam_score",
                    "open_exam_answer" -> teacherContext(payload, extractLong(payload, "teacherId", true), true);
            case "begin_an_exam",
                    "save_exam_progress",
                    "submit_exam_answers",
                    "list_my_exam_records" ->
                    studentContext(payload, extractLong(payload, "studentId", true), true);
            case "get_exam_record_review" -> reviewContext(payload);
            case "get_exam_paper_for_student" -> bestEffortContext(payload);
            default -> bestEffortContext(payload);
        };
    }

    private DispatchContext teacherContext(
            Map<String, Object> originalPayload, Long operatorId, boolean requireId) {
        if (requireId && operatorId == null) {
            throw new IllegalArgumentException("缺少教师标识: teacherId");
        }
        return new DispatchContext(operatorId, operatorId == null ? null : "TEACHER", sanitizePayload(originalPayload));
    }

    private DispatchContext studentContext(
            Map<String, Object> originalPayload, Long operatorId, boolean requireId) {
        if (requireId && operatorId == null) {
            throw new IllegalArgumentException("缺少学生标识: studentId");
        }
        return new DispatchContext(operatorId, operatorId == null ? null : "STUDENT", sanitizePayload(originalPayload));
    }

    private DispatchContext reviewContext(Map<String, Object> originalPayload) {
        Long teacherId = extractLong(originalPayload, "teacherId", true);
        if (teacherId != null) {
            return teacherContext(originalPayload, teacherId, true);
        }
        Long studentId = extractLong(originalPayload, "studentId", true);
        if (studentId != null) {
            return studentContext(originalPayload, studentId, true);
        }

        String role = normalizeRole(extractString(originalPayload, "operatorRole", "role"));
        Long operatorId = extractLong(originalPayload, "operatorId", false);
        if (operatorId == null) {
            operatorId = extractLong(originalPayload, "userId", false);
        }
        if (operatorId != null && role != null) {
            return new DispatchContext(operatorId, role, sanitizePayload(originalPayload));
        }

        throw new IllegalArgumentException("缺少操作者标识: 请提供 teacherId 或 studentId");
    }

    private DispatchContext anonymousContext(Map<String, Object> originalPayload) {
        return new DispatchContext(null, null, sanitizePayload(originalPayload));
    }

    private DispatchContext bestEffortContext(Map<String, Object> originalPayload) {
        Long teacherId = extractLong(originalPayload, "teacherId", true);
        if (teacherId != null) {
            return teacherContext(originalPayload, teacherId, false);
        }
        Long studentId = extractLong(originalPayload, "studentId", true);
        if (studentId != null) {
            return studentContext(originalPayload, studentId, false);
        }

        String role = normalizeRole(extractString(originalPayload, "operatorRole", "role"));
        Long operatorId = extractLong(originalPayload, "operatorId", false);
        if (operatorId == null) {
            operatorId = extractLong(originalPayload, "userId", false);
        }
        if (operatorId != null && role != null) {
            return new DispatchContext(operatorId, role, sanitizePayload(originalPayload));
        }
        return anonymousContext(originalPayload);
    }

    private Map<String, Object> sanitizePayload(Map<String, Object> payload) {
        Map<String, Object> sanitized = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        sanitized.remove("teacherId");
        sanitized.remove("studentId");
        sanitized.remove("operatorId");
        sanitized.remove("operatorRole");
        sanitized.remove("userId");
        sanitized.remove("role");
        return sanitized;
    }

    private Long extractLong(Map<String, Object> payload, String key, boolean fallbackToGenericKeys) {
        Long value = parseLong(payload == null ? null : payload.get(key));
        if (value != null || !fallbackToGenericKeys) {
            return value;
        }
        if (payload == null) {
            return null;
        }
        Long operatorId = parseLong(payload.get("operatorId"));
        if (operatorId != null) {
            return operatorId;
        }
        return parseLong(payload.get("userId"));
    }

    private String extractString(Map<String, Object> payload, String... keys) {
        if (payload == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.valueOf(text.trim());
        }
        return null;
    }

    private Long requirePayloadLong(Map<String, Object> payload, String key) {
        Long value = parseLong(payload == null ? null : payload.get(key));
        if (value == null) {
            throw new IllegalArgumentException("缺少参数: " + key);
        }
        return value;
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        String normalized = role.trim().toUpperCase();
        if (!Set.of("TEACHER", "STUDENT").contains(normalized)) {
            throw new IllegalArgumentException("operatorRole 仅支持 TEACHER 或 STUDENT");
        }
        return normalized;
    }

    private record DispatchContext(
            Long operatorId,
            String operatorRole,
            Map<String, Object> businessPayload) {
    }
}
