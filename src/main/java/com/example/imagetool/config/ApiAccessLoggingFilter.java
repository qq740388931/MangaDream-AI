package com.example.imagetool.config;

import com.example.imagetool.entity.User;
import com.example.imagetool.repository.ApiAccessLogRepository;
import com.example.imagetool.repository.UserRepository;
import com.example.imagetool.util.AuditLogBodySanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 记录所有 /api/** 请求的入参、出参（脱敏）、用户、耗时到表 api_access_log。
 * 以下情况不对请求使用 ContentCachingRequestWrapper，避免与 Spring 解析 @RequestBody 冲突或阻塞：
 * - POST /api/generate、/api/generate-random（大图）
 * - 所有 POST /api/auth/**（含 Google 登录 JSON）
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class ApiAccessLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessLoggingFilter.class);

    private final UserRepository userRepository;
    private final ApiAccessLogRepository apiAccessLogRepository;

    public ApiAccessLoggingFilter(UserRepository userRepository, ApiAccessLogRepository apiAccessLogRepository) {
        this.userRepository = userRepository;
        this.apiAccessLogRepository = apiAccessLogRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/auth")) {
            log.info("[AUTH] 请求已进入服务端 {} {}", request.getMethod(), uri);
        }
        long start = System.currentTimeMillis();
        boolean useRawRequest = shouldNotWrapRequestBody(request);

        if (useRawRequest) {
            ContentCachingResponseWrapper respWrapper = new ContentCachingResponseWrapper(response);
            try {
                filterChain.doFilter(request, respWrapper);
            } finally {
                if (uri != null && uri.startsWith("/api/auth")) {
                    log.info("[AUTH] 请求处理完成（Filter 链返回）{} {}", request.getMethod(), uri);
                }
                finishAndPersist(request, null, respWrapper, start, true);
            }
            return;
        }

        ContentCachingRequestWrapper reqWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper respWrapper = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(reqWrapper, respWrapper);
        } finally {
            finishAndPersist(reqWrapper, reqWrapper, respWrapper, start, false);
        }
    }

    /** 不使用 ContentCachingRequestWrapper，避免消费/缓存请求体与 Controller 读 body 冲突 */
    private static boolean shouldNotWrapRequestBody(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        if (uri.startsWith("/api/auth")) {
            return true;
        }
        return "/api/generate".equals(uri) || "/api/generate-random".equals(uri);
    }

    private void finishAndPersist(HttpServletRequest request,
                                  ContentCachingRequestWrapper cachingRequest,
                                  ContentCachingResponseWrapper respWrapper,
                                  long start,
                                  boolean largePostOmitRequestBody) {
        long duration = System.currentTimeMillis() - start;
        int status = respWrapper.getStatus();
        byte[] respBytes = respWrapper.getContentAsByteArray();
        try {
            respWrapper.copyBodyToResponse();
        } catch (IOException e) {
            log.warn("copyBodyToResponse failed path={}", request.getRequestURI(), e);
        }

        String reqBody;
        if (largePostOmitRequestBody) {
            long len = request.getContentLengthLong();
            reqBody = "[POST body omitted in audit, content-length=" + (len >= 0 ? len : "unknown") + " bytes]";
        } else if (cachingRequest != null) {
            byte[] reqBytes = cachingRequest.getContentAsByteArray();
            reqBody = AuditLogBodySanitizer.maskLargeDataFields(AuditLogBodySanitizer.bytesToUtf8(reqBytes));
        } else {
            reqBody = "";
        }

        String respBody = AuditLogBodySanitizer.maskLargeDataFields(AuditLogBodySanitizer.bytesToUtf8(respBytes));
        if ("/api/auth/google".equals(request.getRequestURI()) && "POST".equalsIgnoreCase(request.getMethod())) {
            String snippet = respBody.length() > 800 ? respBody.substring(0, 800) + "…" : respBody;
            log.warn("[AUTH] /api/auth/google 响应摘要 HTTP状态码={}, body={}", status, snippet);
        }
        persist(request, reqBody, respBody, status, duration);
    }

    private void persist(HttpServletRequest request,
                         String requestBodyMasked,
                         String responseBodyMasked,
                         int httpStatus,
                         long durationMs) {
        try {
            String path = request.getRequestURI();
            String method = request.getMethod();
            String query = request.getQueryString();

            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }
            if (ip == null || ip.isEmpty()) {
                ip = request.getRemoteAddr();
            }
            String ua = request.getHeader("User-Agent");

            Long userId = null;
            String userEmail = null;
            String token = request.getHeader("X-Session-Token");
            if (token != null && !token.isEmpty()) {
                userId = userRepository.findUserIdByToken(token);
                if (userId != null) {
                    User u = userRepository.findById(userId);
                    if (u != null) {
                        userEmail = u.getEmail();
                    }
                }
            }

            apiAccessLogRepository.insert(userId, userEmail, path, method, query, ip, ua,
                    requestBodyMasked, responseBodyMasked, httpStatus, durationMs);
        } catch (Exception e) {
            log.error("api_access_log 写入失败 path={}", request.getRequestURI(), e);
        }
    }
}
