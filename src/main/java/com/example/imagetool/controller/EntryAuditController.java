package com.example.imagetool.controller;

import com.example.imagetool.common.Result;
import com.example.imagetool.entity.User;
import com.example.imagetool.repository.LoginAuditLogRepository;
import com.example.imagetool.repository.UserRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;

/**
 * 前端上报：首次通过 URL 进入（无 token）、关闭后带 localStorage token 重进。
 */
@RestController
@RequestMapping("/api/audit")
public class EntryAuditController {

    private final LoginAuditLogRepository loginAuditLogRepository;
    private final UserRepository userRepository;

    public EntryAuditController(LoginAuditLogRepository loginAuditLogRepository, UserRepository userRepository) {
        this.loginAuditLogRepository = loginAuditLogRepository;
        this.userRepository = userRepository;
    }

    @PostMapping("/entry")
    public Result<Void> entry(@RequestBody(required = false) Map<String, String> body, HttpServletRequest request) {
        if (body == null) {
            body = Collections.emptyMap();
        }
        String eventType = body.get("eventType");
        String ip = clientIp(request);
        String ua = userAgent(request);

        if ("FIRST_URL_ENTRY".equals(eventType)) {
            loginAuditLogRepository.insert("FIRST_URL_ENTRY", null, null, ip, ua);
            return Result.success(null);
        }
        if ("TOKEN_RESUME".equals(eventType)) {
            String token = request.getHeader("X-Session-Token");
            if (token == null || token.trim().isEmpty()) {
                return Result.error(400, "Missing X-Session-Token");
            }
            Long userId = userRepository.findUserIdByToken(token.trim());
            if (userId == null) {
                return Result.error(401, "Invalid or expired session");
            }
            User u = userRepository.findById(userId);
            String email = u != null ? u.getEmail() : null;
            loginAuditLogRepository.insert("TOKEN_RESUME", userId, email, ip, ua);
            return Result.success(null);
        }
        return Result.error(400, "Invalid eventType");
    }

    private static String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        if (ip == null || ip.isEmpty()) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    private static String userAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        if (ua == null) {
            return null;
        }
        return ua.length() > 500 ? ua.substring(0, 500) + "…" : ua;
    }
}
