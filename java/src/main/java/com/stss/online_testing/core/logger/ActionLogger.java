package com.stss.online_testing.core.logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stss.online_testing.common.RequestTraceContext;
import com.stss.online_testing.common.exception.ApiBusinessException;
import com.stss.online_testing.config.LoggerGrpcProperties;
import com.stss.online_testing.entity.ActionLog;
import com.stss.online_testing.mapper.ActionLogMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import loggerServer.v1.Logger;
import loggerServer.v1.LoggerServiceGrpc;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ActionLogger {

    private static final org.slf4j.Logger INTERNAL_LOGGER =
            LoggerFactory.getLogger(ActionLogger.class);

    private static final int PREVIEW_LIMIT = 1000;
    private static final int GRPC_ERROR_LIMIT = 255;
    private static final int STACK_TRACE_LIMIT = 4000;

    private static final Set<String> PROCTOR_ACTIONS =
            Set.of(
                    "begin_an_exam",
                    "save_exam_progress",
                    "submit_exam_answers",
                    "get_exam_record_review",
                    "list_my_exam_records");

    private static final Map<String, Integer> ACTION_OPERATION_IDS =
            Map.ofEntries(
                    Map.entry("add_a_question", 1001),
                    Map.entry("update_a_question", 1002),
                    Map.entry("delete_a_question", 1003),
                    Map.entry("get_a_question", 1004),
                    Map.entry("query_question_bank", 1005),
                    Map.entry("import_questions_by_excel", 1006),
                    Map.entry("create_exam_paper", 1101),
                    Map.entry("update_exam_paper", 1102),
                    Map.entry("generate_exam_paper", 1103),
                    Map.entry("delete_exam_paper", 1113),
                    Map.entry("publish_exam_paper", 1104),
                    Map.entry("withdraw_exam_paper", 1105),
                    Map.entry("query_exam_papers", 1106),
                    Map.entry("preview_exam_paper", 1107),
                    Map.entry("get_exam_paper_for_student", 1108),
                    Map.entry("get_exam_stats", 1109),
                    Map.entry("open_exam_score", 1110),
                    Map.entry("open_exam_answer", 1111),
                    Map.entry("export_exam_scores", 1112),
                    Map.entry("begin_an_exam", 1201),
                    Map.entry("save_exam_progress", 1202),
                    Map.entry("submit_exam_answers", 1203),
                    Map.entry("get_exam_record_review", 1204),
                    Map.entry("list_my_exam_records", 1205));

    private final ActionLogMapper actionLogMapper;
    private final ObjectMapper objectMapper;
    private final LoggerGrpcProperties loggerGrpcProperties;
    private final ManagedChannel managedChannel;
    private final LoggerServiceGrpc.LoggerServiceBlockingStub loggerStub;

    public ActionLogger(
            ActionLogMapper actionLogMapper,
            ObjectMapper objectMapper,
            LoggerGrpcProperties loggerGrpcProperties) {
        this.actionLogMapper = actionLogMapper;
        this.objectMapper = objectMapper;
        this.loggerGrpcProperties = loggerGrpcProperties;

        ManagedChannelBuilder<?> channelBuilder =
                ManagedChannelBuilder.forAddress(
                        loggerGrpcProperties.getHost(), loggerGrpcProperties.getPort());
        if (loggerGrpcProperties.isPlaintext()) {
            channelBuilder.usePlaintext();
        }
        this.managedChannel = channelBuilder.build();
        this.loggerStub = LoggerServiceGrpc.newBlockingStub(managedChannel);
    }

    public void logSuccess(
            String action,
            Long operatorId,
            String operatorRole,
            Map<String, Object> requestPayload,
            Object responseData,
            String message) {
        Map<String, String> stringFields = buildStringFields(action, operatorRole, requestPayload, responseData);
        Map<String, Long> intFields = buildIntFields(requestPayload, responseData, 200, true);

        log(
                buildEntry(
                        resolveLevel(null),
                        resolveService(action),
                        resolveOperationId(action, null),
                        operatorId,
                        message,
                        200,
                        deriveEntityType(action),
                        deriveEntityId(action, requestPayload, responseData),
                        stringFields,
                        intFields,
                        null));
    }

    public void logFailure(
            String action,
            Long operatorId,
            String operatorRole,
            Map<String, Object> requestPayload,
            Exception exception) {
        int statusCode = resolveStatusCode(exception);
        Map<String, String> stringFields = buildStringFields(action, operatorRole, requestPayload, null);
        Map<String, Long> intFields = buildIntFields(requestPayload, null, statusCode, false);

        log(
                buildEntry(
                        resolveLevel(exception),
                        resolveService(action),
                        resolveOperationId(action, exception),
                        operatorId,
                        exception == null ? "系统异常" : exception.getMessage(),
                        statusCode,
                        deriveEntityType(action),
                        deriveEntityId(action, requestPayload, null),
                        stringFields,
                        intFields,
                        exception));
    }

    @PreDestroy
    public void shutdownChannel() {
        managedChannel.shutdownNow();
    }

    private void log(LogEntry entry) {
        boolean delivered = false;
        String grpcError = null;

        try {
            Logger.LogResponse response = sendToGrpc(entry);
            delivered = response.getSuccess();
            if (!response.getSuccess()) {
                grpcError =
                        trim(
                                "logger-service errorCode="
                                        + response.getErrorCode()
                                        + ", message="
                                        + response.getErrorMessage());
            }
        } catch (Exception exception) {
            grpcError = trim(exception.getMessage());
        }

        persistMirror(entry, delivered, grpcError);
    }

    private Logger.LogResponse sendToGrpc(LogEntry entry) {
        try {
            return loggerStub
                    .withDeadlineAfter(loggerGrpcProperties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                    .log(toProto(entry));
        } catch (StatusRuntimeException exception) {
            throw new IllegalStateException("gRPC logger unavailable: " + exception.getStatus(), exception);
        }
    }

    private Logger.LogRequest toProto(LogEntry entry) {
        Logger.LogRequest.Builder builder =
                Logger.LogRequest.newBuilder()
                        .setLevel(entry.level())
                        .setService(defaultString(entry.service()))
                        .setOperationId(entry.operationId())
                        .setTraceId(defaultString(entry.traceId()))
                        .setUserId(defaultString(entry.userId()))
                        .setMessage(defaultString(entry.message()))
                        .setMethod(defaultString(entry.method()))
                        .setPath(defaultString(entry.path()))
                        .setStatusCode(entry.statusCode())
                        .setDurationMS(entry.durationMs())
                        .setEntityType(defaultString(entry.entityType()))
                        .setEntityId(defaultString(entry.entityId()))
                        .setErrorMessage(defaultString(entry.errorMessage()))
                        .setStackTrace(defaultString(entry.stackTrace()));
        builder.putAllStringFields(entry.stringFields());
        builder.putAllIntFields(entry.intFields());
        return builder.build();
    }

    private void persistMirror(LogEntry entry, boolean delivered, String grpcError) {
        try {
            ActionLog log = new ActionLog();
            log.setLevel(entry.level().getNumber());
            log.setService(entry.service());
            log.setOperationId(entry.operationId());
            log.setTraceId(entry.traceId());
            log.setUserId(entry.userId());
            log.setMessage(trim(entry.message()));
            log.setMethod(entry.method());
            log.setPath(entry.path());
            log.setStatusCode(entry.statusCode());
            log.setDurationMs(entry.durationMs());
            log.setEntityType(entry.entityType());
            log.setEntityId(entry.entityId());
            log.setStringFields(trim(safeJson(entry.stringFields())));
            log.setIntFields(trim(safeJson(entry.intFields())));
            log.setErrorMessage(trim(entry.errorMessage()));
            log.setStackTrace(trim(entry.stackTrace(), STACK_TRACE_LIMIT));
            log.setGrpcDelivered(delivered ? 1 : 0);
            log.setGrpcError(trim(grpcError, GRPC_ERROR_LIMIT));
            actionLogMapper.insert(log);
        } catch (Exception exception) {
            INTERNAL_LOGGER.error("Failed to persist action log mirror", exception);
        }
    }

    private LogEntry buildEntry(
            Logger.LogLevel level,
            String service,
            int operationId,
            Long operatorId,
            String message,
            int statusCode,
            String entityType,
            String entityId,
            Map<String, String> stringFields,
            Map<String, Long> intFields,
            Exception exception) {
        RequestTraceContext.TraceSnapshot traceSnapshot = RequestTraceContext.currentOrSynthetic();
        return new LogEntry(
                level,
                service,
                operationId,
                traceSnapshot.getTraceId(),
                operatorId == null ? "" : String.valueOf(operatorId),
                trim(message),
                traceSnapshot.getMethod(),
                traceSnapshot.getPath(),
                statusCode,
                traceSnapshot.elapsedMillis(),
                trim(entityType),
                trim(entityId),
                sanitizeStringMap(stringFields),
                sanitizeLongMap(intFields),
                exception == null ? null : trim(exception.getMessage()),
                exception == null ? null : trim(stackTraceOf(exception), STACK_TRACE_LIMIT));
    }

    private Map<String, String> buildStringFields(
            String action,
            String operatorRole,
            Map<String, Object> requestPayload,
            Object responseData) {
        Map<String, String> stringFields = new LinkedHashMap<>();
        if (action != null) {
            stringFields.put("action", action);
        }
        if (operatorRole != null && !operatorRole.isBlank()) {
            stringFields.put("operatorRole", operatorRole);
        }
        String requestPayloadJson = trim(safeJson(requestPayload));
        if (requestPayloadJson != null) {
            stringFields.put("requestPayload", requestPayloadJson);
        }
        String responsePreview = trim(safeJson(responseData));
        if (responsePreview != null) {
            stringFields.put("responsePreview", responsePreview);
        }
        return stringFields;
    }

    private Map<String, Long> buildIntFields(
            Map<String, Object> requestPayload,
            Object responseData,
            int statusCode,
            boolean success) {
        Map<String, Long> intFields = new LinkedHashMap<>();
        intFields.put("resultCode", (long) statusCode);
        intFields.put("success", success ? 1L : 0L);
        copyLongField(intFields, "id", requestPayload);
        copyLongField(intFields, "courseId", requestPayload);
        copyLongField(intFields, "examId", requestPayload);
        copyLongField(intFields, "recordId", requestPayload);
        copyLongField(intFields, "questionId", requestPayload);
        if (requestPayload != null) {
            copyCollectionSize(intFields, "questionCount", requestPayload.get("questionIds"));
            copyCollectionSize(intFields, "answerCount", requestPayload.get("answers"));
        }
        if (responseData instanceof Map<?, ?> responseMap) {
            copyLongField(intFields, "id", responseMap);
            copyLongField(intFields, "examId", responseMap);
            copyLongField(intFields, "recordId", responseMap);
            copyLongField(intFields, "totalScore", responseMap);
        }
        return intFields;
    }

    private void copyCollectionSize(Map<String, Long> target, String key, Object value) {
        if (value instanceof Iterable<?> iterable) {
            long size = 0;
            for (Object ignored : iterable) {
                size++;
            }
            target.put(key, size);
        }
    }

    private void copyLongField(Map<String, Long> target, String key, Map<?, ?> source) {
        if (source == null || !source.containsKey(key)) {
            return;
        }
        Long value = asLong(source.get(key));
        if (value != null) {
            target.put(key, value);
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, String> sanitizeStringMap(Map<String, String> source) {
        Map<String, String> target = new LinkedHashMap<>();
        if (source == null) {
            return target;
        }
        source.forEach(
                (key, value) -> {
                    if (key != null && value != null) {
                        target.put(trim(key), trim(value));
                    }
                });
        return target;
    }

    private Map<String, Long> sanitizeLongMap(Map<String, Long> source) {
        Map<String, Long> target = new LinkedHashMap<>();
        if (source == null) {
            return target;
        }
        source.forEach(
                (key, value) -> {
                    if (key != null && value != null) {
                        target.put(trim(key), value);
                    }
                });
        return target;
    }

    private Logger.LogLevel resolveLevel(Exception exception) {
        if (exception == null) {
            return Logger.LogLevel.LOG_LEVEL_INFO;
        }
        if (exception instanceof ApiBusinessException
                || exception instanceof IllegalArgumentException
                || exception instanceof IllegalStateException) {
            return Logger.LogLevel.LOG_LEVEL_WARN;
        }
        return Logger.LogLevel.LOG_LEVEL_ERROR;
    }

    private String resolveService(String action) {
        if (action != null && PROCTOR_ACTIONS.contains(action)) {
            return loggerGrpcProperties.getProctorServiceName();
        }
        return loggerGrpcProperties.getApiServiceName();
    }

    private int resolveOperationId(String action, Exception exception) {
        if (action != null && ACTION_OPERATION_IDS.containsKey(action)) {
            return ACTION_OPERATION_IDS.get(action);
        }
        if (exception instanceof ApiBusinessException businessException) {
            return businessException.getCode() * 10;
        }
        if (exception instanceof IllegalArgumentException) {
            return 4001;
        }
        if (exception instanceof IllegalStateException) {
            return 4091;
        }
        return 5000;
    }

    private int resolveStatusCode(Exception exception) {
        if (exception instanceof ApiBusinessException businessException) {
            return businessException.getCode();
        }
        if (exception instanceof IllegalArgumentException) {
            return 400;
        }
        if (exception instanceof IllegalStateException) {
            return 409;
        }
        return 500;
    }

    private String deriveEntityType(String action) {
        if (action == null) {
            return null;
        }
        if (action.contains("record") || PROCTOR_ACTIONS.contains(action)) {
            return "exam_record";
        }
        if (action.contains("question")) {
            return action.contains("bank") ? "question_bank" : "question";
        }
        if (action.contains("exam") || action.contains("paper")) {
            return "exam";
        }
        return "action";
    }

    private String deriveEntityId(
            String action, Map<String, Object> requestPayload, Object responseData) {
        String[] keys = entityLookupKeys(action);
        if (responseData instanceof Map<?, ?> responseMap) {
            for (String key : keys) {
                Object value = responseMap.get(key);
                if (value != null) {
                    return String.valueOf(value);
                }
            }
        }
        if (requestPayload != null) {
            for (String key : keys) {
                Object value = requestPayload.get(key);
                if (value != null) {
                    return String.valueOf(value);
                }
            }
        }
        return null;
    }

    private String[] entityLookupKeys(String action) {
        if (action != null && (action.contains("record") || PROCTOR_ACTIONS.contains(action))) {
            return new String[] {"recordId", "examId", "id"};
        }
        if (action != null && action.contains("question")) {
            return new String[] {"questionId", "id", "courseId"};
        }
        return new String[] {"examId", "id", "recordId"};
    }

    private String safeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private String stackTraceOf(Exception exception) {
        StringWriter stringWriter = new StringWriter();
        exception.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String trim(String value) {
        return trim(value, PREVIEW_LIMIT);
    }

    private String trim(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }

    private record LogEntry(
            Logger.LogLevel level,
            String service,
            int operationId,
            String traceId,
            String userId,
            String message,
            String method,
            String path,
            int statusCode,
            long durationMs,
            String entityType,
            String entityId,
            Map<String, String> stringFields,
            Map<String, Long> intFields,
            String errorMessage,
            String stackTrace) {
    }
}
