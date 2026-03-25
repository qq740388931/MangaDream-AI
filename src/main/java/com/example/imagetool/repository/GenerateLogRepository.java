package com.example.imagetool.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Repository
public class GenerateLogRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public GenerateLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Long userId,
                       String type,
                       String ip,
                       String userAgent,
                       boolean success,
                       String reason,
                       String historyId,
                       String resultUrl,
                       String requestJson,
                       String responseJson) {
        String now = LocalDateTime.now().format(F);
        String safeUa = userAgent;
        if (safeUa != null && safeUa.length() > 500) {
            safeUa = safeUa.substring(0, 500);
        }
        String safeReason = reason;
        if (safeReason != null && safeReason.length() > 255) {
            safeReason = safeReason.substring(0, 255);
        }
        String safeHistoryId = historyId;
        if (safeHistoryId != null && safeHistoryId.length() > 128) {
            safeHistoryId = safeHistoryId.substring(0, 128);
        }
        String safeResultUrl = resultUrl;
        if (safeResultUrl != null && safeResultUrl.length() > 2048) {
            safeResultUrl = safeResultUrl.substring(0, 2048);
        }
        String safeReq = truncate(requestJson, 8000);
        String safeResp = truncate(responseJson, 12000);
        String sql = "INSERT INTO generate_log (user_id, type, ip, user_agent, success, reason, history_id, result_url, request_json, response_json, created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        jdbcTemplate.update(sql, userId, type, ip, safeUa, success ? 1 : 0, safeReason, safeHistoryId, safeResultUrl, safeReq, safeResp, now);
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

    /** 根据 history_id 更新该条日志的结果图 URL（前端轮询到结果后调用） */
    public int updateResultUrlByHistoryId(String historyId, String resultUrl) {
        if (historyId == null || historyId.isEmpty()) {
            return 0;
        }
        String safeResultUrl = resultUrl;
        if (safeResultUrl != null && safeResultUrl.length() > 2048) {
            safeResultUrl = safeResultUrl.substring(0, 2048);
        }
        return jdbcTemplate.update("UPDATE generate_log SET result_url = ? WHERE history_id = ?", safeResultUrl, historyId);
    }
}

