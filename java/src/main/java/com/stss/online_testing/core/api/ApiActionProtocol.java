package com.stss.online_testing.core.api;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

public final class ApiActionProtocol {

    private ApiActionProtocol() {
    }

    @Data
    public static class Request {
        private String action;
        private Map<String, Object> data = new LinkedHashMap<>();
    }

    @Data
    public static class DispatchResult {
        private String message;
        private Object data;

        public DispatchResult(String message, Object data) {
            this.message = message;
            this.data = data;
        }
    }
}
