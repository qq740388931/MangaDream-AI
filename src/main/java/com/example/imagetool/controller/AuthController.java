package com.example.imagetool.controller;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    private static final String BUSY_MSG = "系统繁忙请稍后再试";

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UserRepository userRepository;

    @Value("${app.google.client-id:}")
    private String googleClientId;

    @Value("${app.proxy.host:}")
    private String proxyHost;

    @Value("${app.proxy.port:0}")
    private int proxyPort;

    @Value("${app.auth.dev-enabled:false}")
    private boolean devEnabled;

    @Value("${app.auth.dev-email:}")
    private String devEmail;

    @Value("${app.auth.dev-name:}")
    private String devName;

    public AuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS);
        if (proxyHost != null && !proxyHost.isEmpty() && proxyPort > 0) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            builder.proxy(proxy);
        }
        this.client = builder.build();
    }

    @PostMapping("/google")
    public Result<Map<String, Object>> googleLogin(@RequestBody Map<String, Object> body) {
        if (googleClientId == null || googleClientId.isEmpty()) {
            return Result.error(500, BUSY_MSG);
        }
        String idToken = body != null ? (String) body.get("idToken") : null;
        if (idToken == null || idToken.isEmpty()) {
            return Result.error(400, BUSY_MSG);
        }
        try {
            log.info("开始校验 Google id_token，前 20 字符：{}", idToken.substring(0, Math.min(idToken.length(), 20)));
            Map<String, Object> tokenInfo = verifyIdToken(idToken);
            if (tokenInfo == null) {
                log.warn("Google token 校验返回 null，可能是请求失败或响应体为空");
                return Result.error(401, BUSY_MSG);
            }
            String aud = stringValue(tokenInfo.get("aud"));
            if (!googleClientId.equals(aud)) {
                log.warn("Google token aud 不匹配，期望={}, 实际={}", googleClientId, aud);
                return Result.error(401, BUSY_MSG);
            }
            String sub = stringValue(tokenInfo.get("sub"));
            String email = stringValue(tokenInfo.get("email"));
            String name = stringValue(tokenInfo.get("name"));
            String picture = stringValue(tokenInfo.get("picture"));
            if (sub == null || sub.isEmpty()) {
                log.warn("Google token 中缺少 sub 字段，tokenInfo={}", tokenInfo);
                return Result.error(401, BUSY_MSG);
            }

            User user = userRepository.findByGoogleSub(sub);
            if (user == null) {
                user = userRepository.insert(sub, email, name, picture);
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
            return Result.success(data);
        } catch (Exception e) {
            log.error("Google 登录异常", e);
            return Result.error(500, BUSY_MSG);
        }
    }

    /**
     * 本地开发用的假登录接口，不走 Google，直接把配置里的 devEmail/devName 当成登录用户。
     * 仅当 app.auth.dev-enabled=true 且配置了 dev-email 时生效。
     */
    @PostMapping("/dev-login")
    public Result<Map<String, Object>> devLogin() {
        if (!devEnabled) {
            return Result.error(403, BUSY_MSG);
        }
        if (devEmail == null || devEmail.isEmpty()) {
            return Result.error(500, BUSY_MSG);
        }
        try {
            String sub = "dev-" + devEmail;
            String name = (devName == null || devName.isEmpty()) ? devEmail : devName;
            String picture = null;

            User user = userRepository.findByGoogleSub(sub);
            if (user == null) {
                user = userRepository.insert(sub, devEmail, name, picture);
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
            return Result.success(data);
        } catch (Exception e) {
            log.error("dev 登录异常", e);
            return Result.error(500, BUSY_MSG);
        }
    }

    private Map<String, Object> verifyIdToken(String idToken) throws IOException {
        Request request = new Request.Builder()
                .url(GOOGLE_TOKENINFO_URL + idToken)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("调用 Google tokeninfo 失败，HTTP 状态码={}", response.code());
                return null;
            }
            String resp = response.body().string();
            log.debug("Google tokeninfo 响应: {}", resp);
            return mapper.readValue(resp, Map.class);
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

