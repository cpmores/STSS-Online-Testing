package com.stss.online_testing.common;

import java.util.UUID;

/**
 * 为统一入口请求维护轻量级追踪信息，便于日志模块补齐 traceId / method / path / duration。
 */
public final class RequestTraceContext {

    private static final String INTERNAL_METHOD = "INTERNAL";
    private static final String INTERNAL_PATH = "direct://config-manager";
    private static final ThreadLocal<TraceSnapshot> TRACE_CONTEXT = new ThreadLocal<>();

    private RequestTraceContext() {
    }

    public static void begin(String traceId, String method, String path) {
        TRACE_CONTEXT.set(
                new TraceSnapshot(
                        traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId,
                        method == null || method.isBlank() ? INTERNAL_METHOD : method,
                        path == null || path.isBlank() ? INTERNAL_PATH : path,
                        System.currentTimeMillis()));
    }

    public static TraceSnapshot current() {
        return TRACE_CONTEXT.get();
    }

    /**
     * 直接调用 Controller / ConfigManager 的测试不会经过 HTTP 拦截器，这里补一个兜底快照。
     */
    public static TraceSnapshot currentOrSynthetic() {
        TraceSnapshot snapshot = TRACE_CONTEXT.get();
        if (snapshot != null) {
            return snapshot;
        }
        return new TraceSnapshot(UUID.randomUUID().toString(), INTERNAL_METHOD, INTERNAL_PATH, 0L);
    }

    public static void clear() {
        TRACE_CONTEXT.remove();
    }

    public static final class TraceSnapshot {
        private final String traceId;
        private final String method;
        private final String path;
        private final long startTimeMillis;

        public TraceSnapshot(String traceId, String method, String path, long startTimeMillis) {
            this.traceId = traceId;
            this.method = method;
            this.path = path;
            this.startTimeMillis = startTimeMillis;
        }

        public String getTraceId() {
            return traceId;
        }

        public String getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }

        public long elapsedMillis() {
            if (startTimeMillis <= 0) {
                return 0L;
            }
            return Math.max(0L, System.currentTimeMillis() - startTimeMillis);
        }
    }
}
