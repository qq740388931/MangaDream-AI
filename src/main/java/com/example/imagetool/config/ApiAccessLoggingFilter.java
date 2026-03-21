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
 * 含大图 base64 的 POST（/api/generate、/api/generate-random）不缓存请求体，只记 content-length，避免 OOM。
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
        long start = System.currentTimeMillis();
        boolean largeImagePost = isLargeImagePost(request);

        if (largeImagePost) {
            ContentCachingResponseWrapper respWrapper = new ContentCachingResponseWrapper(response);
            try {
                filterChain.doFilter(request, respWrapper);
            } finally {
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

    private static boolean isLargeImagePost(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
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
