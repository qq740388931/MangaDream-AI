package com.example.imagetool.controller;

import com.example.imagetool.common.Result;
import com.example.imagetool.repository.UserRepository;
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

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
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
    private static final int DEFAULT_WIDTH = 683;
    private static final int DEFAULT_HEIGHT = 1024;
    private static final String RANDOM_DEFAULT_PROMPT = "帮我生成图片：[角色描述]，保持人物原型完全不变，全身/半身构图，如果人物原型缺少了脚和腿可以补充。\n" +
            "[姿势动态描述]。背景设定为[原作动漫名称]的真实三次元世界观场景，包含标志性建筑/自然景观/元素/道具但是人物原型完全不变。画面构图包含前景[模糊元素]和中景,\n" +
            "人物保持照片原光影，但通过环境光反射让人物自然融入动漫背景，形成真实的立体感和纵深层次，高质量，照片级人物 + 背景融合。\n" +
            "人物身边要有[原作动漫名称]的道具，比如某种兽和机车以及武器等.\n" +
            "再来点烟雾什么的增加氛围感";

    private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient.Builder().build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.raphael.bearer-token:}")
    private String bearerToken;

    private final StyleService styleService;
    private final UserRepository userRepository;

    public GenerateController(StyleService styleService, UserRepository userRepository) {
        this.styleService = styleService;
        this.userRepository = userRepository;
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
        if (imageBase64 == null || imageBase64.isEmpty()) {
            return Result.error(400, "缺少 imageBase64");
        }
        Long userId = getUserIdFromRequest(request);
        if (userId == null) {
            return Result.error(401, "请先登录后再生成图片");
        }
        if (!userRepository.deductPoints(userId, 2)) {
            return Result.error(402, "积分不足，每次生成消耗 2 点积分。每日 6 点会把低于 10 分的用户恢复到 10 分。");
        }
        String prompt = styleService.getPromptByStyleId(templateId);
        if (prompt == null || prompt.isEmpty()) {
            prompt = RANDOM_DEFAULT_PROMPT;
        }
        int width = body != null && body.get("width") != null ? ((Number) body.get("width")).intValue() : DEFAULT_WIDTH;
        int height = body != null && body.get("height") != null ? ((Number) body.get("height")).intValue() : DEFAULT_HEIGHT;
        try {
            Map<String, Object> data = submitRaphaelTask(imageBase64, prompt, width, height);
            return Result.success(data);
        } catch (IllegalStateException e) {
            return Result.error(503, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "提交异常: " + e.getMessage());
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
        if (imageBase64 == null || imageBase64.isEmpty()) {
            return Result.error(400, "缺少 imageBase64");
        }
        Long userId = getUserIdFromRequest(request);
        if (userId == null) {
            return Result.error(401, "请先登录后再生成图片");
        }
        if (!userRepository.deductPoints(userId, 2)) {
            return Result.error(402, "积分不足，每次生成消耗 2 点积分。每日 6 点会把低于 10 分的用户恢复到 10 分。");
        }
        int width = body != null && body.get("width") != null ? ((Number) body.get("width")).intValue() : DEFAULT_WIDTH;
        int height = body != null && body.get("height") != null ? ((Number) body.get("height")).intValue() : DEFAULT_HEIGHT;

        try {
            Map<String, Object> data = submitRaphaelTask(imageBase64, RANDOM_DEFAULT_PROMPT, width, height);
            return Result.success(data);
        } catch (IllegalStateException e) {
            return Result.error(503, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "提交异常: " + e.getMessage());
        }
    }

    private Map<String, Object> submitRaphaelTask(String imageBase64, String prompt, int width, int height) throws Exception {
        if (bearerToken == null || bearerToken.isEmpty()) {
            throw new IllegalStateException("未配置 app.raphael.bearer-token，请在 application.yml 中配置");
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
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("Raphael 提交失败: " + response.code());
            }
            String respStr = response.body().string();
            TaskSubmitResponse parsed = mapper.readValue(respStr, TaskSubmitResponse.class);
            if (!parsed.isSuccess() || parsed.getData() == null) {
                throw new RuntimeException(parsed.getMessage() != null ? parsed.getMessage() : "提交失败");
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
                    return Result.error(502, "查询任务状态失败: " + response.code());
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
            return Result.error(500, "查询异常: " + e.getMessage());
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
        if (token == null || token.isEmpty()) {
            return null;
        }
        return userRepository.findUserIdByToken(token);
    }
}
