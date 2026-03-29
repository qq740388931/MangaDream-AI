package com.example.imagetool.controller;

import com.example.imagetool.common.ClientIpUtil;
import com.example.imagetool.common.ErrorMessageUtil;
import com.example.imagetool.common.Result;
import com.example.imagetool.entity.User;
import com.example.imagetool.repository.UserRepository;
import com.example.imagetool.repository.GenerateLogRepository;
import com.example.imagetool.service.StyleService;
import com.example.imagetool.utilitys.TaskSubmitResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片生成相关接口（对接 Raphael AI 图像编辑）
 * 提供：按风格生成、随机生成、轮询任务结果
 */
@RestController
@RequestMapping("/api")
public class GenerateController {

    private static final String RAPHAEL_SUBMIT_URL = "https://raphael.app/api/ai-image-editor/task";
    private static final String RAPHAEL_HISTORY_URL = "https://raphael.app/api/history";
    private static final Logger log = LoggerFactory.getLogger(GenerateController.class);
    private static final int DEFAULT_WIDTH = 683;
    private static final int DEFAULT_HEIGHT = 1024;
    private static final String RANDOM_DEFAULT_PROMPT = "保持人物原型完全不变（脸部、服装、身材比例锁定，不做任何修改）。构图：全身或半身构图，若原图人物缺脚可自动补全，严格遵循原图的姿势动态。背景：采用该角色所属原作的真实三次元世界观场景，包含标志性建筑、自然景观、或经典元素/道具。背景需有明确的空间层次（前景模糊元素 + 中景主体 + 远景环境），通过环境光反射与漫反射让人物自然融入背景，营造照片级的真实感与纵深。道具与氛围：在人物身边添加符合原作的标志性道具，增加动态烟雾/雾气/薄雾（自然飘散，不遮挡人物面部），提升氛围感。特效：适当增加光影特效（如光晕、镜头光斑、粒子、魔法辉光等），提升画面神秘感与沉浸感。整体要求：最终输出为照片级真实感，人物与背景融合自然，光影统一，不做卡通化处理";

    private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient.Builder().build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.raphael.bearer-token:}")
    private String bearerToken;
    @Value("${app.auth.dev-enabled:false}")
    private boolean devEnabled;
    @Value("${app.auth.dev-email:}")
    private String devEmail;
    @Value("${app.auth.dev-name:}")
    private String devName;

    private final StyleService styleService;
    private final UserRepository userRepository;
    private final GenerateLogRepository generateLogRepository;

    public GenerateController(StyleService styleService,
                              UserRepository userRepository,
                              GenerateLogRepository generateLogRepository) {
        this.styleService = styleService;
        this.userRepository = userRepository;
        this.generateLogRepository = generateLogRepository;
    }

    /**
     * 按风格生成图片（「生成此风格图片」入口）
     * 请求体：imageBase64（必填）、templateId（风格/模板 id，必填）、width/height（可选）
     * 根据 templateId 查对应风格的 prompt，提交到 Raphael，返回 historyId、pollIntervalMs 供前端轮询
     */
    @PostMapping("/generate")
    public Result<Map<String, Object>> generate(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
                                                HttpServletRequest request) {
        String imageBase64 = body != null ? (String) body.get("imageBase64") : null;
        Object tid = body != null ? body.get("templateId") : null;
        int templateId = tid instanceof Number ? ((Number) tid).intValue() : (tid != null ? Integer.parseInt(tid.toString()) : 0);
        int width = body != null && body.get("width") != null ? ((Number) body.get("width")).intValue() : DEFAULT_WIDTH;
        int height = body != null && body.get("height") != null ? ((Number) body.get("height")).intValue() : DEFAULT_HEIGHT;
        Long userId = getUserIdFromRequest(request);

        if (imageBase64 == null || imageBase64.isEmpty()) {
            log.warn("generate(style): 缺少图片, userId={}", userId);
            String reqJ = buildStyleRequestSnapshot(body, templateId, width, height, imageBase64, null);
            String respJ = snapResponse(400, "Missing image (imageBase64)", null);
            logGenerateFull(userId, "style", request, false, "missing_image", null, reqJ, respJ);
            return Result.error(400, "Missing image (imageBase64)");
        }
        if (userId == null) {
            log.warn("generate(style): 未登录");
            String reqJ = buildStyleRequestSnapshot(body, templateId, width, height, imageBase64, null);
            String respJ = snapResponse(401, "Please sign in (send X-Session-Token header)", null);
            logGenerateFull(null, "style", request, false, "not_logged_in", null, reqJ, respJ);
            return Result.error(401, "Please sign in (send X-Session-Token header)");
        }
        if (!userRepository.deductPoints(userId, 5)) {
            log.warn("generate(style): 积分不足, userId={}", userId);
            String reqJ = buildStyleRequestSnapshot(body, templateId, width, height, imageBase64, null);
            String respJ = snapResponse(402, "Insufficient points. Please upgrade to membership or try again after tomorrow's free points are granted.", null);
            logGenerateFull(userId, "style", request, false, "points_not_enough", null, reqJ, respJ);
            return Result.error(402, "Insufficient points. Please upgrade to membership or try again after tomorrow's free points are granted.");
        }
        Integer pointsAfterDeduct = null;
        try {
            com.example.imagetool.entity.User u = userRepository.findById(userId);
            pointsAfterDeduct = u != null ? u.getPoints() : null;
        } catch (Exception ignored) {
        }
        String prompt = styleService.getPromptByStyleId(templateId);
        if (prompt == null || prompt.isEmpty()) {
            prompt = RANDOM_DEFAULT_PROMPT;
        }
        String reqSnapshot = buildStyleRequestSnapshot(body, templateId, width, height, imageBase64, prompt);
        try {
            Map<String, Object> data = submitRaphaelTask(imageBase64, prompt, width, height);
            if (pointsAfterDeduct != null) {
                data.put("remainingPoints", pointsAfterDeduct);
            }
            String historyId = data.get("historyId") != null ? data.get("historyId").toString() : null;
            String respJ = snapResponse(200, "success", data);
            logGenerateFull(userId, "style", request, true, "ok", historyId, reqSnapshot, respJ);
            return Result.success(data);
        } catch (IllegalStateException e) {
            String userMsg = ErrorMessageUtil.userFacing(e);
            String detail = ErrorMessageUtil.detailForLog(e);
            String respJ = snapResponse(503, detail, null);
            logGenerateFull(userId, "style", request, false, "submit_error: " + detail, null, reqSnapshot, respJ);
            log.error("Generate(style) submit error", e);
            return Result.error(503, userMsg);
        } catch (Exception e) {
            String userMsg = ErrorMessageUtil.userFacing(e);
            String detail = ErrorMessageUtil.detailForLog(e);
            String respJ = snapResponse(500, detail, null);
            logGenerateFull(userId, "style", request, false, "submit_error: " + detail, null, reqSnapshot, respJ);
            log.error("Generate(style) unexpected error", e);
            return Result.error(500, userMsg);
        }
    }

    /**
     * 随机生成图片（「随机生成」按钮入口）
     * 请求体：imageBase64（必填）、width/height（可选），不传模板，使用固定默认 prompt 提交到 Raphael，
     * 返回 historyId、pollIntervalMs 供前端轮询
     */
    @PostMapping("/generate-random")
    public Result<Map<String, Object>> generateRandom(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        String imageBase64 = body != null ? (String) body.get("imageBase64") : null;
        int width = body != null && body.get("width") != null ? ((Number) body.get("width")).intValue() : DEFAULT_WIDTH;
        int height = body != null && body.get("height") != null ? ((Number) body.get("height")).intValue() : DEFAULT_HEIGHT;
        Long userId = getUserIdFromRequest(request);

        if (imageBase64 == null || imageBase64.isEmpty()) {
            log.warn("generate(random): 缺少图片, userId={}", userId);
            String reqJ = buildRandomRequestSnapshot(body, width, height, imageBase64);
            String respJ = snapResponse(400, "Missing image (imageBase64)", null);
            logGenerateFull(userId, "random", request, false, "missing_image", null, reqJ, respJ);
            return Result.error(400, "Missing image (imageBase64)");
        }
        if (userId == null) {
            log.warn("generate(random): 未登录");
            String reqJ = buildRandomRequestSnapshot(body, width, height, imageBase64);
            String respJ = snapResponse(401, "Please sign in (send X-Session-Token header)", null);
            logGenerateFull(null, "random", request, false, "not_logged_in", null, reqJ, respJ);
            return Result.error(401, "Please sign in (send X-Session-Token header)");
        }
        if (!userRepository.deductPoints(userId, 5)) {
            log.warn("generate(random): 积分不足, userId={}", userId);
            String reqJ = buildRandomRequestSnapshot(body, width, height, imageBase64);
            String respJ = snapResponse(402, "Insufficient points. Please upgrade to membership or try again after tomorrow's free points are granted.", null);
            logGenerateFull(userId, "random", request, false, "points_not_enough", null, reqJ, respJ);
            return Result.error(402, "Insufficient points. Please upgrade to membership or try again after tomorrow's free points are granted.");
        }
        Integer pointsAfterDeduct = null;
        try {
            com.example.imagetool.entity.User u = userRepository.findById(userId);
            pointsAfterDeduct = u != null ? u.getPoints() : null;
        } catch (Exception ignored) {
        }

        String reqSnapshot = buildRandomRequestSnapshot(body, width, height, imageBase64);
        try {
            Map<String, Object> data = submitRaphaelTask(imageBase64, RANDOM_DEFAULT_PROMPT, width, height);
            if (pointsAfterDeduct != null) {
                data.put("remainingPoints", pointsAfterDeduct);
            }
            String historyId = data.get("historyId") != null ? data.get("historyId").toString() : null;
            String respJ = snapResponse(200, "success", data);
            logGenerateFull(userId, "random", request, true, "ok", historyId, reqSnapshot, respJ);
            return Result.success(data);
        } catch (IllegalStateException e) {
            String userMsg = ErrorMessageUtil.userFacing(e);
            String detail = ErrorMessageUtil.detailForLog(e);
            String respJ = snapResponse(503, detail, null);
            logGenerateFull(userId, "random", request, false, "submit_error: " + detail, null, reqSnapshot, respJ);
            log.error("Generate(random) submit error", e);
            return Result.error(503, userMsg);
        } catch (Exception e) {
            String userMsg = ErrorMessageUtil.userFacing(e);
            String detail = ErrorMessageUtil.detailForLog(e);
            String respJ = snapResponse(500, detail, null);
            logGenerateFull(userId, "random", request, false, "submit_error: " + detail, null, reqSnapshot, respJ);
            log.error("Generate(random) unexpected error", e);
            return Result.error(500, userMsg);
        }
    }

    private Map<String, Object> submitRaphaelTask(String imageBase64, String prompt, int width, int height) throws Exception {
        if (bearerToken == null || bearerToken.isEmpty()) {
            throw new IllegalStateException("app.raphael.bearer-token is not configured (see application.yml)");
        }

        String jsonBody = buildSubmitBody(imageBase64, prompt, width, height);
        RequestBody requestBody = RequestBody.create(MEDIA_TYPE_JSON, jsonBody);

        // 核心提交：统一由两个接口复用
        Request raphaelRequest = new Request.Builder()
                .url(RAPHAEL_SUBMIT_URL)
                .post(requestBody)
                // Referer / Origin
                .addHeader("Referer", "https://raphael.app/zh")
                .addHeader("Origin", "https://raphael.app")
                // Cookie（application.yml 中配置完整 Cookie 串）
                .addHeader("Cookie", bearerToken)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

                // 其他关键头（尽量贴近浏览器抓包）
               // .addHeader("Accept-Encoding", "gzip, deflate, br, zstd")
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9")
                .addHeader("Content-Type", "application/json")
                .addHeader("sec-ch-ua", "\"Not:A-Brand\";v=\"99\", \"Google Chrome\";v=\"145\", \"Chromium\";v=\"145\"")
                .addHeader("sec-ch-ua-mobile", "?1")
                .addHeader("sec-ch-ua-platform", "\"Android\"")
                .addHeader("sec-fetch-dest", "empty")
                .addHeader("sec-fetch-mode", "cors")
                .addHeader("sec-fetch-site", "same-origin")
                .addHeader("Cache-Control", "no-cache")
                .build();

        try (Response response = client.newCall(raphaelRequest).execute()) {
            String respStr = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String snippet = respStr.length() > 800 ? respStr.substring(0, 800) + "…" : respStr;
                throw new RuntimeException("Raphael submit failed: HTTP " + response.code()
                        + (snippet.isEmpty() ? "" : ", body=" + snippet));
            }
            TaskSubmitResponse parsed = mapper.readValue(respStr, TaskSubmitResponse.class);
            if (!parsed.isSuccess() || parsed.getData() == null) {
                String snippet = respStr.length() > 800 ? respStr.substring(0, 800) + "…" : respStr;
                throw new RuntimeException((parsed.getMessage() != null ? parsed.getMessage() : "Submit failed")
                        + ", raw=" + snippet);
            }
            Map<String, Object> data = new HashMap<>();
            data.put("historyId", parsed.getData().getHistoryId());
            data.put("pollIntervalMs", parsed.getData().getPollIntervalMs());
            return data;
        }
    }

    /**
     * 轮询生成任务结果（前端提交任务后按 historyId 轮询此接口）
     * 请求 Raphael 历史接口，从 data.item.images 取第二个元素的 url 作为结果图地址；
     * 未完成时 data 为空字符串，前端继续轮询；完成时返回图片 URL 字符串
     */
    @GetMapping("/raphael-history/{historyId}")
    public Result<String> raphaelHistory(@PathVariable String historyId) {
        if (bearerToken == null || bearerToken.isEmpty()) {
            return Result.success("");
        }
        try {
            Request request = new Request.Builder()
                    .url(RAPHAEL_HISTORY_URL + "/" + historyId)
                    .get()
                    .addHeader("Cookie", bearerToken)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.error("Raphael history HTTP 失败: status={}, historyId={}（前端 msg 仍为统一提示）", response.code(), historyId);
                    return Result.error(502, ErrorMessageUtil.USER_BUSY);
                }
                String respStr = response.body().string();
                Map<?, ?> map = mapper.readValue(respStr, Map.class);
                String imageUrl = extractRaphaelOutputUrlFromDataItem(map);
                if (imageUrl != null && !imageUrl.isEmpty()) {
                    return Result.success(imageUrl);
                }
                return Result.success("");
            }
        } catch (Exception e) {
            log.error("Raphael history query error historyId={}, detail={}", historyId, ErrorMessageUtil.detailForLog(e), e);
            return Result.error(500, ErrorMessageUtil.userFacing(e));
        }
    }

    /** 从 Raphael history 响应中取 data.item.images 的第二个元素的 url 返回 */
    @SuppressWarnings("unchecked")
    private String extractRaphaelOutputUrlFromDataItem(Map<?, ?> root) {
        Object data = root.get("data");
        if (!(data instanceof Map)) {
            return null;
        }
        Object item = ((Map<?, ?>) data).get("item");
        if (!(item instanceof Map)) {
            return null;
        }
        Object images = ((Map<?, ?>) item).get("images");
        if (!(images instanceof java.util.List) || ((java.util.List<?>) images).size() < 2) {
            return null;
        }
        Object second = ((java.util.List<?>) images).get(1);
        if (!(second instanceof Map)) {
            return null;
        }
        Object urlObj = ((Map<?, ?>) second).get("url");
        if (urlObj == null) {
            return null;
        }
        String s = urlObj.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private String buildSubmitBody(String imageBase64, String prompt, int width, int height) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("prompt", prompt);
        payload.put("input_image", imageBase64);
        payload.put("input_image_mime_type", mimeFromDataUrl(imageBase64));
        payload.put("input_image_extension", extFromDataUrl(imageBase64));
        payload.put("width", width);
        payload.put("height", height);
        return mapper.writeValueAsString(payload);
    }

    private static String mimeFromDataUrl(String s) {
        if (s == null) {
            return "image/jpeg";
        }
        String lower = s.toLowerCase();
        if (lower.contains("image/png")) {
            return "image/png";
        }
        if (lower.contains("image/webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static String extFromDataUrl(String s) {
        if (s == null) {
            return "jpg";
        }
        String lower = s.toLowerCase();
        if (lower.contains("image/png")) {
            return "png";
        }
        if (lower.contains("image/webp")) {
            return "webp";
        }
        return "jpg";
    }

    private Long getUserIdFromRequest(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String token = request.getHeader("X-Session-Token");
        if (token != null && !token.trim().isEmpty()) {
            Long userId = userRepository.findUserIdByToken(token.trim());
            if (userId != null) {
                return userId;
            }
        }
        return resolveDevUserIdFallback(request);
    }

    private Long resolveDevUserIdFallback(HttpServletRequest request) {
        String remoteIp = request != null ? ClientIpUtil.resolve(request) : "";
        boolean localRequest = ClientIpUtil.isLoopbackOrLocal(request);
        if (!devEnabled && !localRequest) {
            return null;
        }
        String effectiveDevEmail = (devEmail == null || devEmail.trim().isEmpty()) ? "local-dev@mangadream.ai" : devEmail.trim();
        String effectiveDevName = (devName == null || devName.trim().isEmpty()) ? effectiveDevEmail : devName.trim();
        String sub = "dev-" + effectiveDevEmail;
        try {
            User user = userRepository.findByGoogleSub(sub);
            if (user == null) {
                user = userRepository.insert(sub, effectiveDevEmail, effectiveDevName, null);
            } else {
                userRepository.updateLoginInfo(user.getId(), effectiveDevEmail, effectiveDevName, null);
            }
            return (user != null) ? user.getId() : null;
        } catch (Exception e) {
            log.warn("resolveDevUserIdFallback failed, ip={}, sub={}", remoteIp, sub, e);
            return null;
        }
    }

    private void logGenerateFull(Long userId,
                                 String type,
                                 HttpServletRequest request,
                                 boolean success,
                                 String reason,
                                 String historyId,
                                 String requestJson,
                                 String responseJson) {
        if (generateLogRepository == null) {
            return;
        }
        try {
            String ip = null;
            String ua = null;
            if (request != null) {
                ip = ClientIpUtil.resolve(request);
                ua = request.getHeader("User-Agent");
            }
            generateLogRepository.insert(userId, type, ip, ua, success, reason, historyId, null, requestJson, responseJson);
        } catch (Exception e) {
            log.error("generate_log 表写入失败 userId={}, type={}, reason={}, historyId={}",
                    userId, type, reason, historyId, e);
        }
    }

    /** 不落库图片 base64，仅记录长度与业务参数 */
    private String buildStyleRequestSnapshot(Map<String, Object> body,
                                             int templateId,
                                             int width,
                                             int height,
                                             String imageBase64,
                                             String promptResolved) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "style");
        if (body != null && body.get("templateId") != null) {
            m.put("templateId", body.get("templateId"));
        } else {
            m.put("templateId", templateId);
        }
        m.put("width", width);
        m.put("height", height);
        m.put("imageBase64Length", imageBase64 != null ? imageBase64.length() : 0);
        if (promptResolved != null) {
            m.put("promptLength", promptResolved.length());
        }
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildRandomRequestSnapshot(Map<String, Object> body,
                                              int width,
                                              int height,
                                              String imageBase64) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "random");
        m.put("width", width);
        m.put("height", height);
        m.put("imageBase64Length", imageBase64 != null ? imageBase64.length() : 0);
        m.put("defaultPromptLength", RANDOM_DEFAULT_PROMPT.length());
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 与前端收到的 Result JSON 一致：code、msg、data（成功时） */
    private String snapResponse(int code, String msg, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("msg", msg != null ? msg : "");
        if (data != null && !data.isEmpty()) {
            m.put("data", data);
        }
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"code\":" + code + "}";
        }
    }

    /**
     * 前端轮询到生成结果后，把结果图 URL 回写到对应日志记录
     * 请求体：historyId、resultUrl
     */
    @PostMapping("/generate-log/result")
    public Result<Void> updateGenerateLogResult(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        Object hid = body != null ? body.get("historyId") : null;
        Object url = body != null ? body.get("resultUrl") : null;
        String historyId = hid != null ? hid.toString().trim() : null;
        String resultUrl = url != null ? url.toString().trim() : null;
        if (historyId == null || historyId.isEmpty()) {
            log.warn("generate-log/result: 缺少 historyId");
            return Result.error(400, "Missing historyId");
        }
        generateLogRepository.updateResultUrlByHistoryId(historyId, resultUrl);
        return Result.success(null);
    }
}
