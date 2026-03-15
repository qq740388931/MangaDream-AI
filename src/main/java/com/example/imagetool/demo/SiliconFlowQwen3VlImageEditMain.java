package com.example.imagetool.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 可直接运行的 main：
 * - 读取本地图片 + 文字提示词
 * - 第 1 步：调用 /v1/chat/completions，使用视觉语言模型 Qwen/Qwen3-VL-32B-Instruct 生成“图片编辑提示词”
 * - 第 2 步：调用 /v1/images/generations，使用图像编辑模型 Qwen/Qwen-Image-Edit-2509 生成图片，并下载保存到本地
 *
 * 注意：
 * 1) Qwen3-VL 返回的是“编辑指令/提示词”（文本），真正的出图由 Qwen-Image-Edit 模型完成。
 * 2) API Key 建议用环境变量；你也可以填常量 API_KEY（注意泄露风险）。
 *
 * 运行示例（PowerShell）：
 *  $env:SILICONFLOW_API_KEY="你的key"
 *  mvn -q -Dexec.mainClass=com.example.imagetool.demo.SiliconFlowQwen3VlImageEditMain exec:java -Dexec.args="D:\test.jpg 把图片改成赛博朋克风格，霓虹灯，高对比"
 */
public class SiliconFlowQwen3VlImageEditMain {

    // 硅基流动 OpenAI 兼容 base_url（OpenAPI servers: https://api.siliconflow.cn/v1）
    // 如果你账号/网络环境只支持 .com，也可以改回 https://api.siliconflow.com/v1
    private static final String BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String VLM_MODEL = "Qwen/Qwen3-VL-32B-Instruct";
    // 真正“出图/改图”的模型（文档示例）
    private static final String IMAGE_EDIT_MODEL = "Qwen/Qwen-Image-Edit-2509";

    /**
     * 直接把你的 API Key 填在这里即可运行（不需要环境变量）。
     *
     * 安全提示：一旦代码上传/分享/被反编译，Key 就可能泄露并被盗刷。
     * 如果你确认只在本机个人项目中使用，可以这么做；否则建议改回环境变量。
     */
    private static final String API_KEY = "sk-phoiprcjjwyxleuhqebupeyesuvlryrmettptixgmrwqsniz";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        // ================== 你只需要改这里两行（不想传参就填这里） ==================
        // 1) 图片路径：例如 "D:\\test.png"
        String imagePath = "C:\\Users\\zzc\\Desktop\\DSCF8860.JPG";
        // 2) 提示词：例如 "把图片改成赛博朋克风格，霓虹灯，高对比"
        String prompt = "改成黑白照片";
        // 3) 输出图片保存路径：例如 "D:\\out.png"（留空则保存到当前目录 output.png）
        String outputPath = "D:\\图片";
        // ========================================================================

        // 支持两种传参方式：
        // 1) 推荐：--image <path> --prompt <text...>
        // 2) 兼容旧写法：<path> <text...>
        Map<String, String> named = parseNamedArgs(args);
        String imagePathFromArgs = null;
        String promptFromArgs = null;

        if (named.containsKey("image") || named.containsKey("prompt")) {
            imagePathFromArgs = named.get("image");
            promptFromArgs = named.get("prompt");
        } else {
            // 兼容旧写法：第一个参数是图片路径，后面拼成提示词
            if (args.length < 2) {
                // 如果你在上面两个字符串里已经填了，就不强制要求传参
                if (isBlank(imagePath) || isBlank(prompt)) {
                    printUsageAndExit();
                    return;
                }
            } else {
                imagePathFromArgs = args[0];
                promptFromArgs = joinArgs(args, 1);
            }
        }

        // 如果命令行传了参数，就覆盖你在代码里写的默认值
        if (!isBlank(imagePathFromArgs)) {
            imagePath = imagePathFromArgs;
        }
        if (!isBlank(promptFromArgs)) {
            prompt = promptFromArgs;
        }

        if (isBlank(imagePath) || isBlank(prompt)) {
            printUsageAndExit();
            return;
        }

        String apiKey = firstNonBlank(
                API_KEY,
                System.getenv("SILICONFLOW_API_KEY"),
                System.getProperty("siliconflow.apiKey")
        );
        if (apiKey == null) {
            System.err.println("未检测到 API Key。请在代码常量 API_KEY 中填写，或设置环境变量 SILICONFLOW_API_KEY，或 JVM 参数 -Dsiliconflow.apiKey=xxx");
            System.exit(2);
        }
        System.out.println("base_url=" + BASE_URL + "，vlm_model=" + VLM_MODEL + "，image_model=" + IMAGE_EDIT_MODEL);
        System.out.println("apiKey prefix=" + maskPrefix(apiKey) + "，len=" + apiKey.length());
        System.out.println("当前工作目录=" + new File(".").getAbsolutePath());

        File imageFile = new File(imagePath);
        if (!imageFile.exists() || !imageFile.isFile()) {
            System.err.println("图片不存在或不是文件：" + imagePath);
            System.exit(2);
        }

        String mime = guessMimeType(imageFile.getName());
        byte[] imageBytes = readAllBytes(imageFile);
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUri = "data:" + mime + ";base64," + b64;

        // ===== 第 1 步：让 VLM 输出“可用于图像编辑模型”的提示词（JSON 文本） =====
        String instruction =
                "你是一个图片编辑助手。用户会给你：一张图片 + 一段“修改图片的需求”。\n" +
                "请输出 JSON（不要输出多余文字），字段固定为：\n" +
                "{\n" +
                "  \"edited_prompt\": \"用于图生图(img2img)的正向提示词，包含风格、主体、细节、质量词\",\n" +
                "  \"negative_prompt\": \"需要避免的元素/瑕疵\",\n" +
                "  \"steps\": [\"简短步骤1\", \"步骤2\", \"步骤3\"],\n" +
                "  \"notes\": \"对强度/分辨率/构图等建议\"\n" +
                "}\n" +
                "用户需求如下：\n" + prompt;

        Map<String, Object> chatPayload = buildChatPayload(dataUri, instruction);
        String chatRespText = postJson(BASE_URL + "/chat/completions", apiKey, MAPPER.writeValueAsString(chatPayload));

        // 解析 OpenAI 兼容返回：choices[0].message.content
        Map<String, Object> chatResp = MAPPER.readValue(chatRespText, new TypeReference<Map<String, Object>>() {});
        String content = extractContent(chatResp);

        System.out.println("=== 模型返回（原始 content）===");
        System.out.println(content);

        // 尝试把 content 当 JSON 解析，拿到 edited_prompt / negative_prompt
        String editedPrompt = null;
        String negativePrompt = null;
        try {
            Map<String, Object> contentJson = MAPPER.readValue(content, new TypeReference<Map<String, Object>>() {});
            Object ep = contentJson.get("edited_prompt");
            Object np = contentJson.get("negative_prompt");
            if (ep != null) {
                editedPrompt = String.valueOf(ep);
            }
            if (np != null) {
                negativePrompt = String.valueOf(np);
            }
        } catch (Exception ignore) {
            // content 不是严格 JSON 时，回退到用户原始 prompt
        }
        if (isBlank(editedPrompt)) {
            editedPrompt = prompt;
        }

        // ===== 第 2 步：调用图片生成/编辑接口，获取图片 URL，并下载保存 =====
        String outPath = isBlank(outputPath) ? defaultOutFile(imageFile).getAbsolutePath() : outputPath.trim();
        String imageUrl = generateEditedImage(apiKey, dataUri, editedPrompt, negativePrompt);
        System.out.println("生成图片URL=" + imageUrl);
        long savedBytes = downloadToFile(imageUrl, new File(outPath));
        System.out.println("=== 已保存图片 ===");
        System.out.println(new File(outPath).getAbsolutePath());
        System.out.println("文件大小=" + savedBytes + " bytes");
    }

    private static void printUsageAndExit() {
        System.err.println("用法（推荐）：");
        System.err.println("  --image <图片路径> --prompt <提示词>");
        System.err.println();
        System.err.println("示例（PowerShell）：");
        System.err.println("  $env:SILICONFLOW_API_KEY=\"你的key\"");
        System.err.println("  mvn -q -Dexec.mainClass=com.example.imagetool.demo.SiliconFlowQwen3VlImageEditMain exec:java -Dexec.args=\"--image D:\\\\test.png --prompt 把图片改成赛博朋克风格，霓虹灯，高对比\"");
        System.err.println();
        System.err.println("也支持旧写法：<图片路径> <提示词...>");
        System.exit(2);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().length() == 0;
    }

    private static String maskPrefix(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        int n = Math.min(6, t.length());
        return t.substring(0, n) + "...";
    }

    /**
     * 解析形如：--image <path> --prompt <text...>
     * - 支持别名：-i / -p
     * - prompt 会把后续所有参数拼接为一个字符串（直到结束）
     */
    private static Map<String, String> parseNamedArgs(String[] args) {
        Map<String, String> out = new HashMap<>();
        if (args == null || args.length == 0) {
            return out;
        }

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--image".equals(a) || "-i".equals(a)) {
                if (i + 1 < args.length) {
                    out.put("image", args[i + 1]);
                    i++;
                }
                continue;
            }
            if ("--prompt".equals(a) || "-p".equals(a)) {
                if (i + 1 < args.length) {
                    out.put("prompt", joinArgs(args, i + 1));
                    break;
                }
            }
        }

        return out;
    }

    private static Map<String, Object> buildChatPayload(String dataUri, String text) {
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", dataUri);
        imageUrl.put("detail", "high");

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", imageUrl);

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", text);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", Arrays.asList(imagePart, textPart));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", VLM_MODEL);
        payload.put("messages", Collections.singletonList(message));
        payload.put("temperature", 0.2);
        return payload;
    }

    /**
     * 调用 /images/generations 使用图像编辑模型生成图片，并返回第 1 张图片的 URL
     * 文档显示 URL 有效期 1 小时，请及时下载保存。
     */
    private static String generateEditedImage(String apiKey, String dataUri, String prompt, String negativePrompt) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", IMAGE_EDIT_MODEL);
        payload.put("prompt", prompt);
        // 下面两个参数在文档示例里用于 Qwen/Qwen-Image-Edit-2509
        payload.put("num_inference_steps", 20);
        payload.put("cfg", 4);
        payload.put("image", dataUri);
        if (!isBlank(negativePrompt)) {
            payload.put("negative_prompt", negativePrompt);
        }

        String respText = postJson(BASE_URL + "/images/generations", apiKey, MAPPER.writeValueAsString(payload));
        Map<String, Object> resp = MAPPER.readValue(respText, new TypeReference<Map<String, Object>>() {});
        return extractFirstImageUrl(resp);
    }

    @SuppressWarnings("unchecked")
    private static String extractFirstImageUrl(Map<String, Object> resp) throws IOException {
        Object imagesObj = resp.get("images");
        if (!(imagesObj instanceof List)) {
            throw new IOException("图片生成返回中缺少 images 字段：" + String.valueOf(resp));
        }
        List<Object> images = (List<Object>) imagesObj;
        if (images.isEmpty()) {
            throw new IOException("图片生成返回 images 为空：" + String.valueOf(resp));
        }
        Object first = images.get(0);
        if (!(first instanceof Map)) {
            throw new IOException("图片生成返回 images[0] 结构异常：" + String.valueOf(resp));
        }
        Map<String, Object> img0 = (Map<String, Object>) first;
        Object url = img0.get("url");
        if (url == null) {
            throw new IOException("图片生成返回缺少 url：" + String.valueOf(resp));
        }
        return String.valueOf(url);
    }

    private static long downloadToFile(String url, File outFile) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readToString(conn.getErrorStream());
                throw new IOException("下载图片失败 HTTP " + code + "：" + err);
            }
            System.out.println("下载Content-Type=" + conn.getContentType());
            byte[] bytes = readAllBytes(conn.getInputStream());
            writeAllBytes(outFile, bytes);
            return bytes.length;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static File defaultOutFile(File inputImage) {
        File dir = inputImage.getParentFile();
        if (dir == null) {
            dir = new File(".");
        }
        String baseName = inputImage.getName();
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) {
            baseName = baseName.substring(0, dot);
        }
        String name = "edited_" + baseName + "_" + System.currentTimeMillis() + ".png";
        return new File(dir, name);
    }

    private static String postJson(String url, String apiKey, String jsonBody) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);

            byte[] out = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readToString(is);
            if (code < 200 || code >= 300) {
                String hint = "";
                if (code == 401) {
                    hint = "（401 未授权：通常是 API Key 没填/填错/已失效，或 base_url 选错。可优先尝试 https://api.siliconflow.cn/v1 ）";
                }
                throw new IOException("HTTP " + code + " 返回：" + resp + " " + hint);
            }
            return resp;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractContent(Map<String, Object> resp) {
        Object choicesObj = resp.get("choices");
        if (!(choicesObj instanceof List)) {
            return String.valueOf(resp);
        }
        List<Object> choices = (List<Object>) choicesObj;
        if (choices.isEmpty()) {
            return String.valueOf(resp);
        }
        Object first = choices.get(0);
        if (!(first instanceof Map)) {
            return String.valueOf(resp);
        }
        Map<String, Object> choice0 = (Map<String, Object>) first;
        Object messageObj = choice0.get("message");
        if (!(messageObj instanceof Map)) {
            return String.valueOf(resp);
        }
        Map<String, Object> message = (Map<String, Object>) messageObj;
        Object contentObj = message.get("content");
        return contentObj == null ? "" : String.valueOf(contentObj);
    }

    private static String guessMimeType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "image/jpeg";
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static String readToString(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream input = in;
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String joinArgs(String[] args, int startIdx) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < args.length; i++) {
            if (i > startIdx) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && v.trim().length() > 0) {
                return v.trim();
            }
        }
        return null;
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        try (InputStream input = in;
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static void writeAllBytes(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                throw new IOException("创建目录失败：" + parent.getAbsolutePath());
            }
        }
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(bytes);
        }
    }
}

