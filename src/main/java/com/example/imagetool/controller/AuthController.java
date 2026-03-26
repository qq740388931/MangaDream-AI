package com.example.imagetool.controller;

import com.example.imagetool.common.ErrorMessageUtil;
import com.example.imagetool.common.Result;
import com.example.imagetool.entity.User;
import com.example.imagetool.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.imagetool.repository.LoginAuditLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 登录相关接口：使用 Google 登录，前端通过 Google Identity Services 拿到 id_token 后调用
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String GOOGLE_TOKENINFO_URL = "https://oauth2.googleapis.com/tokeninfo?id_token=";

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UserRepository userRepository;
    private final LoginAuditLogRepository loginAuditLogRepository;

    @Value("${app.google.client-id:}")
    private String googleClientId;

    @Value("${app.auth.dev-enabled:false}")
    private boolean devEnabled;

    @Value("${app.auth.dev-email:}")
    private String devEmail;

    @Value("${app.auth.dev-name:}")
    private String devName;

    public AuthController(UserRepository userRepository,
                          LoginAuditLogRepository loginAuditLogRepository,
                          @Value("${app.proxy.host:}") String proxyHost,
                          @Value("${app.proxy.port:0}") int proxyPort) {
        this.userRepository = userRepository;
        this.loginAuditLogRepository = loginAuditLogRepository;
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS);
        if (proxyHost != null && !proxyHost.isEmpty() && proxyPort > 0) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            builder.proxy(proxy);
        }
        this.client = builder.build();
    }

    /**
     * 前端读取 Google 客户端 ID，与后端 app.google.client-id 保持一致（避免两处手写不一致）。
     */
    @GetMapping("/config")
    public Result<Map<String, String>> publicAuthConfig() {
        Map<String, String> data = new HashMap<>();
        data.put("googleClientId", googleClientId != null ? googleClientId : "");
        return Result.success(data);
    }

    /**
     * 当前登录用户资料（含最新积分、VIP 剩余天），供前端每次点开头像前刷新。
     * 请求头：{@code X-Session-Token}
     */
    @GetMapping("/profile")
    public Result<Map<String, Object>> profile(HttpServletRequest request) {
        String token = request.getHeader("X-Session-Token");
        if (token == null || token.trim().isEmpty()) {
            return Result.error(401, "Missing session");
        }
        Long userId = userRepository.findUserIdByToken(token.trim());
        if (userId == null) {
            return Result.error(401, "Invalid or expired session");
        }
        User user = userRepository.findById(userId);
        if (user == null) {
            return Result.error(404, "User not found");
        }
        Map<String, Object> profile = new HashMap<>();
        profile.put("id", user.getId());
        profile.put("email", user.getEmail());
        profile.put("name", user.getName());
        profile.put("avatarUrl", user.getAvatarUrl());
        profile.put("points", user.getPoints());
        profile.put("vipDaysLeft", calcVipDaysLeft(user.getVipExpireAt()));
        return Result.success(profile);
    }

    @PostMapping("/google")
    public Result<Map<String, Object>> googleLogin(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        log.warn("[GOOGLE_AUTH] Controller 收到 /api/auth/google（已进入业务方法）");
        if (googleClientId == null || googleClientId.isEmpty()) {
            log.error("Google 登录失败: 未配置 app.google.client-id");
            return Result.error(500, "app.google.client-id is not configured");
        }
        String idToken = body != null ? (String) body.get("idToken") : null;
        if (idToken == null || idToken.isEmpty()) {
            log.warn("Google 登录失败: 请求体缺少 idToken");
            return Result.error(400, "Missing idToken");
        }
        try {
            log.info("开始校验 Google id_token，前 20 字符：{}", idToken.substring(0, Math.min(idToken.length(), 20)));
            Map<String, Object> tokenInfo = verifyIdToken(idToken);
            if (tokenInfo == null) {
                log.warn("Google token 校验返回 null，可能是请求失败或响应体为空");
                return Result.error(401, "Google token verification failed (no valid tokeninfo response)");
            }
            String aud = stringValue(tokenInfo.get("aud"));
            if (!googleClientId.equals(aud)) {
                log.warn("Google token aud 不匹配，期望={}, 实际={}", googleClientId, aud);
                return Result.error(401, "Google token aud does not match app.google.client-id; check OAuth client ID in Google Cloud Console");
            }
            String sub = stringValue(tokenInfo.get("sub"));
            String email = stringValue(tokenInfo.get("email"));
            String name = stringValue(tokenInfo.get("name"));
            String picture = stringValue(tokenInfo.get("picture"));
            if (sub == null || sub.isEmpty()) {
                log.warn("Google token 中缺少 sub 字段，tokenInfo={}", tokenInfo);
                return Result.error(401, "Google token is missing user id (sub)");
            }

            User user = userRepository.findByGoogleSub(sub);
            boolean newUser = false;
            if (user == null) {
                user = userRepository.insert(sub, email, name, picture);
                newUser = true;
            } else {
                userRepository.updateLoginInfo(user.getId(), email, name, picture);
            }

            String sessionToken = userRepository.createSessionToken(user.getId());

            Map<String, Object> data = new HashMap<>();
            Map<String, Object> profile = new HashMap<>();
            profile.put("id", user.getId());
            profile.put("email", user.getEmail());
            profile.put("name", user.getName());
            profile.put("avatarUrl", user.getAvatarUrl());
            profile.put("points", user.getPoints());
            profile.put("vipDaysLeft", calcVipDaysLeft(user.getVipExpireAt()));
            data.put("token", sessionToken);
            data.put("profile", profile);
            log.info("[GOOGLE_AUTH] 登录成功 userId={}, email={}, newUser={}", user.getId(), user.getEmail(), newUser);
            try {
                loginAuditLogRepository.insert(newUser ? "GOOGLE_SIGNUP" : "GOOGLE_LOGIN", user.getId(), user.getEmail(), clientIp(request), userAgent(request));
            } catch (Exception auditEx) {
                log.warn("login_audit_log 写入失败", auditEx);
            }
            return Result.success(data);
        } catch (Exception e) {
            log.error("[GOOGLE_AUTH] 登录过程异常", e);
            return Result.error(500, ErrorMessageUtil.fromThrowable(e));
        }
    }

    /**
     * 本地开发用的假登录接口，不走 Google，直接把配置里的 devEmail/devName 当成登录用户。
     * 仅当 app.auth.dev-enabled=true 且配置了 dev-email 时生效。
     */
    @PostMapping("/dev-login")
    public Result<Map<String, Object>> devLogin(HttpServletRequest request) {
        if (!devEnabled) {
            log.warn("dev-login 被拒绝: app.auth.dev-enabled=false");
            return Result.error(403, "dev-login is disabled (app.auth.dev-enabled=false)");
        }
        if (devEmail == null || devEmail.isEmpty()) {
            log.error("dev-login 失败: 未配置 app.auth.dev-email");
            return Result.error(500, "app.auth.dev-email is not configured");
        }
        try {
            String sub = "dev-" + devEmail;
            String name = (devName == null || devName.isEmpty()) ? devEmail : devName;
            String picture = null;

            User user = userRepository.findByGoogleSub(sub);
            boolean newUser = false;
            if (user == null) {
                user = userRepository.insert(sub, devEmail, name, picture);
                newUser = true;
            } else {
                userRepository.updateLoginInfo(user.getId(), devEmail, name, picture);
            }

            String sessionToken = userRepository.createSessionToken(user.getId());

            Map<String, Object> data = new HashMap<>();
            Map<String, Object> profile = new HashMap<>();
            profile.put("id", user.getId());
            profile.put("email", user.getEmail());
            profile.put("name", user.getName());
            profile.put("avatarUrl", user.getAvatarUrl());
            profile.put("points", user.getPoints());
            profile.put("vipDaysLeft", calcVipDaysLeft(user.getVipExpireAt()));
            data.put("token", sessionToken);
            data.put("profile", profile);
            try {
                loginAuditLogRepository.insert(newUser ? "DEV_SIGNUP" : "DEV_LOGIN", user.getId(), user.getEmail(), clientIp(request), userAgent(request));
            } catch (Exception auditEx) {
                log.warn("login_audit_log 写入失败", auditEx);
            }
            return Result.success(data);
        } catch (Exception e) {
            log.error("dev 登录异常", e);
            return Result.error(500, ErrorMessageUtil.fromThrowable(e));
        }
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

    private Map<String, Object> verifyIdToken(String idToken) throws IOException {
        Request request = new Request.Builder()
                .url(GOOGLE_TOKENINFO_URL + idToken)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String snippet = raw.length() > 800 ? raw.substring(0, 800) + "…" : raw;
                log.warn("[GOOGLE_AUTH] tokeninfo HTTP 失败 code={}, 响应片段={}", response.code(), snippet);
                return null;
            }
            if (raw.isEmpty()) {
                log.warn("[GOOGLE_AUTH] tokeninfo 响应体为空");
                return null;
            }
            log.info("[GOOGLE_AUTH] tokeninfo 校验成功，响应长度={}", raw.length());
            return mapper.readValue(raw, Map.class);
        }
    }

    private static String stringValue(Object o) {
        return o == null ? null : o.toString();
    }

    private static long calcVipDaysLeft(String vipExpireAt) {
        if (vipExpireAt == null || vipExpireAt.trim().isEmpty()) {
            return 0L;
        }
        try {
            // 兼容 "yyyy-MM-dd HH:mm:ss" 或 "yyyy-MM-dd"
            String s = vipExpireAt.trim();
            LocalDateTime endDateTime;
            if (s.length() <= 10) {
                LocalDate d = LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                endDateTime = d.atTime(23, 59, 59);
            } else {
                endDateTime = LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            LocalDateTime now = LocalDateTime.now();
            if (endDateTime.isBefore(now)) {
                return 0L;
            }
            long days = ChronoUnit.DAYS.between(now.toLocalDate(), endDateTime.toLocalDate()) + 1;
            return Math.max(days, 0L);
        } catch (Exception e) {
            return 0L;
        }
    }
}

