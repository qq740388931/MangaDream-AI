package com.example.imagetool.config;

import com.example.imagetool.common.ErrorMessageUtil;
import com.example.imagetool.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：对外返回可读错误信息（msg），服务端仍打完整堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 请求体不是合法 JSON 时，在进入 Controller 之前就会抛错，此前不会有 AuthController 日志。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBadRequestBody(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析为 JSON（常见于前端未发 JSON 或 Content-Type 不对）: {}", e.getMessage());
        return Result.error(400, ErrorMessageUtil.fromThrowable(e));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("未捕获异常 [{}]: {}", e.getClass().getName(), e.getMessage(), e);
        return Result.error(500, ErrorMessageUtil.fromThrowable(e));
    }
}

