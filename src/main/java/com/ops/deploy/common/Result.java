package com.ops.deploy.common;

/**
 * 统一响应包装：{code, message, data}，code=0 表示成功。
 */
public class Result<T> {

    public static final int SUCCESS_CODE = 0;

    private int code;
    private String message;
    private T data;

    public Result() {
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = SUCCESS_CODE;
        r.message = "ok";
        r.data = data;
        return r;
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        r.data = null;
        return r;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
