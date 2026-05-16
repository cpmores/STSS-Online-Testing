package com.stss.online_testing.core.api;

import com.stss.online_testing.common.Result;
import com.stss.online_testing.common.exception.ApiBusinessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiBusinessException.class)
    public Result<Object> handleBusinessError(ApiBusinessException exception) {
        return Result.error(exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Object> handleBadRequest(IllegalArgumentException exception) {
        return Result.error(400, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public Result<Object> handleConflict(IllegalStateException exception) {
        return Result.error(409, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Object> handleInternalError(Exception exception) {
        return Result.error(500, "系统异常: " + exception.getMessage());
    }
}
