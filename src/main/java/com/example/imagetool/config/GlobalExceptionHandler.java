package com.example.imagetool.config;

import com.example.imagetool.common.ErrorMessageUtil;
import com.example.imagetool.common.Result;
import com.example.imagetool.repository.ErrorLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;

/**
 * 统一异常处理：对外返回可读错误信息（msg），服务端仍打完整堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorLogRepository errorLogRepository;

    public GlobalExceptionHandler(ErrorLogRepository errorLogRepository) {
        this.errorLogRepository = errorLogRepository;
    }

    /**
     * 请求体不是合法 JSON 时，在进入 Controller 之前就会抛错，此前不会有 AuthController 日志。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBadRequestBody(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.warn("请求体无法解析为 JSON（常见于前端未发 JSON 或 Content-Type 不对）: {}", e.getMessage());
        persistError(request, e, 400);
        return Result.error(400, "请求数据格式有误");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("未捕获异常 {}", ErrorMessageUtil.detailForLog(e), e);
        persistError(request, e, 500);
        return Result.error(500, ErrorMessageUtil.userFacing(e));
    }

    private void persistError(HttpServletRequest request, Throwable e, int httpStatus) {
        try {
            errorLogRepository.insertFromRequest(request, e, httpStatus);
        } catch (Exception ignored) {
            // 避免写库失败影响正常错误响应
        }
    }
}

