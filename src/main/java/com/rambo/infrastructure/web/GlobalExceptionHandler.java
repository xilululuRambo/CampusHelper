package com.rambo.infrastructure.web;

import com.rambo.common.constants.CodeConstants;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.exception.BusinessException;
import com.rambo.common.result.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {


    /**
     * 方法参数校验异常处理
     *
     * @param e 方法参数校验异常
     * @return 处理结果
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldError().getDefaultMessage();
        log.error("参数校验失败：{}", message);
        return Result.fail(CodeConstants.PARAM_ERROR, message);
    }

    /**
     *
     * @param e 单个参数校验异常
     * @return 处理结果
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<?> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().iterator().next().getMessage();
        return Result.fail(CodeConstants.PARAM_ERROR, message);
    }

    /**
     * 表单/Query 参数绑定失败（非 @RequestBody 场景，如 @ModelAttribute 绑定错误）。
     * 注意：MethodArgumentNotValidException 是其子类，会被更精确的 handler 先捕获，此处仅兜底。
     *
     * @param e 参数绑定异常
     * @return 处理结果
     */
    @ExceptionHandler(BindException.class)
    public Result<?> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldError() != null
                ? e.getBindingResult().getFieldError().getDefaultMessage()
                : MessageConstants.PARAM_ERROR;
        log.error("参数绑定失败：{}", message);
        return Result.fail(CodeConstants.PARAM_ERROR, message);
    }

    /**
     * 缺少必需的请求参数（@RequestParam(required = true) 未传）
     *
     * @param e 缺参异常
     * @return 处理结果
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<?>> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        String message = "缺少必要参数: " + e.getParameterName();
        log.warn(message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(CodeConstants.PARAM_ERROR, message));
    }

    /**
     * 缺少必需的请求头（@RequestHeader(required = true) 未传）
     *
     * @param e 缺头异常
     * @return 处理结果
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Result<?>> handleMissingRequestHeaderException(MissingRequestHeaderException e) {
        String message = "缺少必要请求头: " + e.getHeaderName();
        log.warn(message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(CodeConstants.PARAM_ERROR, message));
    }

    /**
     * 请求参数类型不匹配（如传了非数字的 ID）
     *
     * @param e 类型不匹配异常
     * @return 处理结果
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<?>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        String message = "参数 " + e.getName() + " 类型不合法";
        log.warn("{}，期望类型：{}", message, e.getRequiredType());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(CodeConstants.PARAM_ERROR, message));
    }

    /**
     * 请求方式错误（GET/POST 不匹配）
     *
     * @param e 请求方式异常
     * @return 处理结果
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        String message = "请求方式不支持: " + e.getMethod() + "，请使用 " + e.getSupportedHttpMethods();
        log.warn(message);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(CodeConstants.METHOD_NOT_ALLOWED, message));
    }

    /**
     * 上传文件超出大小限制
     *
     * @param e 上传大小异常
     * @return 处理结果
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<?>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        String message = "上传文件过大，超出大小限制";
        log.warn("上传文件超限：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.fail(CodeConstants.PARAM_ERROR, message));
    }

    /**
     * 业务异常处理
     * <p>HTTP 层保持 200，业务结果由 body.code 表达（业务码 600/500）。
     * 这是业务模块与前端约定的既有契约：业务失败提示依赖 body.msg 直接弹出。</p>
     *
     * @param e 业务异常
     * @return 处理结果
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        if (e.getCause() != null) {
            // 携带底层 cause 的业务异常（如 OSS 上传失败）打完整堆栈，便于排障
            log.error("业务异常：{}", e.getMessage(), e);
        } else {
            log.error("业务异常：{}", e.getMessage());
        }
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 请求体解析异常（如枚举值非法、JSON格式错误）
     *
     * @param e 请求体解析异常
     * @return 处理结果
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return Result.fail(CodeConstants.PARAM_ERROR, MessageConstants.PARAM_ERROR);
    }

    /**
     * 静态资源未找到异常（如 favicon.ico），静默处理不打日志
     *
     * @param e 资源未找到异常
     * @return 处理结果
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<?>> handleNoResourceFoundException(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(CodeConstants.NOT_FOUND, e.getMessage()));
    }

    /**
     * 全局异常处理
     *
     * @param e 全局异常
     * @return 处理结果
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("全局异常：", e);
        return Result.fail(MessageConstants.SYSTEM_ERROR);
    }

}
