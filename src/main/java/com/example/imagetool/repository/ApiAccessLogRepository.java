package com.example.imagetool.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全站 /api 接口审计：入参、出参（脱敏后）、用户、耗时。
 */
@Repository
public class ApiAccessLogRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ApiAccessLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Long userId,
                       String userEmail,
                       String path,
                       String method,
                       String queryString,
                       String ip,
                       String userAgent,
                       String requestBodyMasked,
                       String responseBodyMasked,
                       int httpStatus,
                       long durationMs) {
        String now = LocalDateTime.now().format(F);
        String safeUa = truncate(userAgent, 500);
        String safePath = truncate(path, 512);
        String safeQuery = truncate(queryString, 1024);
        String safeReq = truncate(requestBodyMasked, 8000);
        String safeResp = truncate(responseBodyMasked, 8000);
        String safeEmail = truncate(userEmail, 255);
        String sql = "INSERT INTO api_access_log (user_id, user_email, path, method, query_string, ip, user_agent, "
                + "request_body, response_body, http_status, duration_ms, created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        jdbcTemplate.update(sql, userId, safeEmail, safePath, method, safeQuery, ip, safeUa,
                safeReq, safeResp, httpStatus, durationMs, now);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
