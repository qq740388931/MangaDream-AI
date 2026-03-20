package com.example.imagetool.config;

import com.example.imagetool.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：
 * - 对外返回固定文案：系统繁忙请稍后再试
 * - 同时在服务端打印真实异常堆栈，方便排查
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BUSY_MSG = "系统繁忙请稍后再试";

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("Unhandled server exception", e);
        return Result.error(500, BUSY_MSG);
    }
}

