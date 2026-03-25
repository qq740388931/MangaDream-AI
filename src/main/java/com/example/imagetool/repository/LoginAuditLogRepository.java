package com.example.imagetool.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 用户进入/登录语义审计：首次 URL 进入、带 token 重进、Google/开发登录成功。
 */
@Repository
public class LoginAuditLogRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public LoginAuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String eventType, Long userId, String userEmail, String ip, String userAgent) {
        String now = LocalDateTime.now().format(F);
        String safeUa = truncate(userAgent, 500);
        String safeEmail = truncate(userEmail, 255);
        String sql = "INSERT INTO login_audit_log (event_type, user_id, user_email, ip, user_agent, created_at) VALUES (?,?,?,?,?,?)";
        jdbcTemplate.update(sql, eventType, userId, safeEmail, ip, safeUa, now);
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
