package com.stss.online_testing.common;

import lombok.Data;

@Data
public class Result<T> {
    private Integer code; // 状态码：200表示成功，500表示失败
    private String message; // 提示信息
    private T data; // 实际承载的数据

    public static <T> Result<T> success(T data) {
        return success("操作成功", data);
    }

    public static <T> Result<T> success(String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
