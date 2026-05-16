package com.stss.online_testing.common.exception;

public class ApiBusinessException extends RuntimeException {

    private final int code;

    public ApiBusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static ApiBusinessException badRequest(String message) {
        return new ApiBusinessException(400, message);
    }

    public static ApiBusinessException forbidden(String message) {
        return new ApiBusinessException(403, message);
    }

    public static ApiBusinessException notFound(String message) {
        return new ApiBusinessException(404, message);
    }

    public static ApiBusinessException conflict(String message) {
        return new ApiBusinessException(409, message);
    }

    public static ApiBusinessException unprocessable(String message) {
        return new ApiBusinessException(422, message);
    }
}
