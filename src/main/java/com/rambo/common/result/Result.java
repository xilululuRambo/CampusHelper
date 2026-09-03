package com.rambo.common.result;

import lombok.Data;

import java.io.Serializable;

@Data
public class Result<T> implements Serializable {

    private Integer code;
    private String msg;
    private T data;

    // 通用成功（无数据）
    public static <T> Result<T> success() {
        Result<T> result = new Result<>();
        result.setCode(Results.SUCCESS.getCode());
        result.setMsg(Results.SUCCESS.getMsg());
        return result;
    }

    // 成功 + 带数据
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(Results.SUCCESS.getCode());
        result.setMsg(Results.SUCCESS.getMsg());
        result.setData(data);
        return result;
    }

    // 成功 + 自定义消息 + 带数据
    public static <T> Result<T> success(T data, String msg) {
        Result<T> result = new Result<>();
        result.setCode(Results.SUCCESS.getCode());
        result.setMsg(msg);
        result.setData(data);
        return result;
    }

    // 失败（默认消息）
    public static <T> Result<T> fail() {
        Result<T> result = new Result<>();
        result.setCode(Results.FAIL.getCode());
        result.setMsg(Results.FAIL.getMsg());
        return result;
    }

    // 失败 + 自定义消息
    public static <T> Result<T> fail(String msg) {
        Result<T> result = new Result<>();
        result.setCode(Results.FAIL.getCode());
        result.setMsg(msg);
        return result;
    }

    // 失败 + 自定义 code + msg（给全局异常用）
    public static <T> Result<T> fail(Integer code, String msg) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }
}