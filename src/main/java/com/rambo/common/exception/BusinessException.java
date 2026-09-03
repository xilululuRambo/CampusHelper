package com.rambo.common.exception;

import com.rambo.common.constants.CodeConstants;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final Integer code;

    /**
     * 构造函数
     * @param message 异常信息
     */
    public BusinessException(String message) {
        super(message);
        this.code = CodeConstants.FAIL;
    }

    /**
     * 构造函数
     * @param code 异常码
     * @param message 异常信息
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 构造函数（携带原始异常，便于排障定位底层原因，如 OSS 上传失败）
     *
     * @param message 异常信息
     * @param cause   原始异常
     */
    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = CodeConstants.FAIL;
    }

    /**
     * 构造函数（携带原始异常，便于排障定位底层原因）
     *
     * @param code    异常码
     * @param message 异常信息
     * @param cause   原始异常
     */
    public BusinessException(Integer code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

}
