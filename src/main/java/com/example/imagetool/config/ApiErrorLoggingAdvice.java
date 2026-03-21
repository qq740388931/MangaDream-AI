package com.example.imagetool.config;

import com.example.imagetool.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 任意接口返回 {@link Result} 且 code≠200 时打一条日志，便于与 msg 对照排查。
 */
@ControllerAdvice
public class ApiErrorLoggingAdvice implements ResponseBodyAdvice<Result<?>> {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorLoggingAdvice.class);

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return Result.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Result<?> beforeBodyWrite(Result<?> body,
                                     MethodParameter returnType,
                                     MediaType selectedContentType,
                                     Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                     ServerHttpRequest request,
                                     ServerHttpResponse response) {
        if (body != null && body.getCode() != null && body.getCode() != 200) {
            String path = "?";
            if (request instanceof ServletServerHttpRequest) {
                path = ((ServletServerHttpRequest) request).getServletRequest().getRequestURI();
            }
            log.warn("接口业务错误: code={}, msg={}, path={}", body.getCode(), body.getMsg(), path);
            if (path != null && path.contains("/api/auth")) {
                log.warn("[GOOGLE_AUTH] 业务失败（见上一条 code/msg），path={}", path);
            }
        }
        return body;
    }
}
