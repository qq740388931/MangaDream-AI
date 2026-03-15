package com.example.imagetool.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
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
 * 修复 HTTP 500 问题：
 * 1. 移除模型不支持的 quality/response_format 参数
 * 2. 限制分辨率不超过 2048x2048（模型上限）
 * 3. 调整 cfg/num_inference_steps 到模型兼容范围
 * 4. 下载后手动转无损PNG，避免服务器端参数错误
 */
public class doubaoDemo {

    private static final String BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String VLM_MODEL = "Qwen/Qwen3-VL-32B-Instruct";
    private static final String IMAGE_EDIT_MODEL = "Qwen/Qwen-Image-Edit-2509";
    private static final String API_KEY = "sk-phoiprcjjwyxleuhqebupeyesuvlryrmettptixgmrwqsniz";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 模型支持的最大分辨率
    private static final int MAX_RESOLUTION = 2048;

    public static void main(String[] args) throws Exception {
        String imagePath = "C:\\Users\\zzc\\Desktop\\DSCF8860.JPG";
        String prompt = "任务：图像编辑\n" +
                "原始图像：[上传你的照片]\n" +
                "\n" +
                "保持不变的区域（严格锁定）：\n" +
                "- 面部结构：脸型轮廓、眉眼间距、鼻梁高度、嘴唇形状\n" +
                "- 发型：完全保持原样，包括发色、刘海、发际线\n" +
                "- 服装细节：黑色Jeep衬衫（包括领口、袖口）、黑色裤子、白色运动鞋\n" +
                "- 姿态：侧身站立，右手轻抚额头\n" +
                "\n" +
                "需要修改的区域：\n" +
                "- 背景：替换为郁郁葱葱的温室花园\n" +
                "- 新增元素：白色华丽铁艺长椅、半透明蕾丝窗帘、复古花卉地毯、带花纹茶杯\n" +
                "- 环境光效：柔和的漫射阳光透过树叶，斑驳光影落在人物和地面\n" +
                "- 整体风格：马卡龙色调，梦幻空灵，柔焦效果，电影感打光\n" +
                "\n" +
                "技术要求：\n" +
                "- 人物和背景的光影方向一致，自然融合\n" +
                "- 保持8K超精细画质，专业摄影级细节\n" +
                "- 背景适度虚化，突出人物";
        String outputPath = "D:\\图片";

        Map<String, String> named = parseNamedArgs(args);
        String imagePathFromArgs = null;
        String promptFromArgs = null;

        if (named.containsKey("image") || named.containsKey("prompt")) {
            imagePathFromArgs = named.get("image");
            promptFromArgs = named.get("prompt");
        } else {
            if (args.length < 2) {
                if (isBlank(imagePath) || isBlank(prompt)) {
                    printUsageAndExit();
                    return;
                }
            } else {
                imagePathFromArgs = args[0];
                promptFromArgs = joinArgs(args, 1);
            }
        }

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
            System.err.println("未检测到 API Key。请在代码常量 API_KEY 中填写，或设置环境变量 SILICONFLOW_API_KEY");
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

        // 获取并限制原图分辨率（不超过模型上限）
        int[] imgSize = getImageSize(imageFile);
        int[] limitedSize = limitResolution(imgSize[0], imgSize[1]);
        int width = limitedSize[0];
        int height = limitedSize[1];
        System.out.println("原图分辨率：" + imgSize[0] + "x" + imgSize[1]);
        System.out.println("模型处理分辨率：" + width + "x" + height);

        String mime = guessMimeType(imageFile.getName());
        byte[] imageBytes = readAllBytes(imageFile);
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUri = "data:" + mime + ";base64," + b64;

        String instruction =
                "你是一个图片编辑助手。用户会给你：一张图片 + 一段“修改图片的需求,仅修改图片的背景和风格，严格保留人物的面部特征、五官、发型、身材、服装，不做任何改变”。\n" +
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

        Map<String, Object> chatResp = MAPPER.readValue(chatRespText, new TypeReference<Map<String, Object>>() {});
        String content = extractContent(chatResp);

        System.out.println("=== 模型返回（原始 content）===");
        System.out.println(content);

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
        }
        if (isBlank(editedPrompt)) {
            editedPrompt = prompt;
        }

        String outPath = isBlank(outputPath) ? defaultOutFile(imageFile).getAbsolutePath() : outputPath.trim();
        String imageUrl = generateEditedImage(apiKey, dataUri, editedPrompt, negativePrompt, width, height);
        System.out.println("生成图片URL=" + imageUrl);
        long savedBytes = downloadAndSaveAsLossless(imageUrl, new File(outPath));
        System.out.println("=== 已保存图片 ===");
        System.out.println(new File(outPath).getAbsolutePath());
        System.out.println("文件大小=" + savedBytes + " bytes");
    }

    // 获取图片宽高
    private static int[] getImageSize(File imageFile) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(imageFile)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    return new int[]{width, height};
                } finally {
                    reader.dispose();
                }
            }
        }
        return new int[]{1024, 1024};
    }

    // 限制分辨率不超过模型上限（等比例缩放）
    private static int[] limitResolution(int originalWidth, int originalHeight) {
        if (originalWidth <= MAX_RESOLUTION && originalHeight <= MAX_RESOLUTION) {
            return new int[]{originalWidth, originalHeight};
        }
        double ratio = Math.min((double) MAX_RESOLUTION / originalWidth, (double) MAX_RESOLUTION / originalHeight);
        int newWidth = (int) Math.round(originalWidth * ratio);
        int newHeight = (int) Math.round(originalHeight * ratio);
        return new int[]{newWidth, newHeight};
    }

    // 下载并保存为无损PNG格式
    private static long downloadAndSaveAsLossless(String url, File outFile) throws IOException {
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

            BufferedImage image = ImageIO.read(conn.getInputStream());
            if (image == null) {
                throw new IOException("无法解析下载的图片内容");
            }

            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            File finalOutFile = outFile;
            if (!finalOutFile.getName().toLowerCase().endsWith(".png")) {
                String name = finalOutFile.getName().replaceAll("\\.[^.]+$", "") + ".png";
                finalOutFile = new File(finalOutFile.getParentFile(), name);
            }

            ImageIO.write(image, "PNG", finalOutFile);

            return finalOutFile.length();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void printUsageAndExit() {
        System.err.println("用法（推荐）：");
        System.err.println("  --image <图片路径> --prompt <提示词>");
        System.err.println();
        System.err.println("示例（PowerShell）：");
        System.err.println("  $env:SILICONFLOW_API_KEY=\"你的key\"");
        System.err.println("  mvn -q -Dexec.mainClass=com.example.imagetool.demo.doubaoDemo exec:java -Dexec.args=\"--image D:\\\\test.png --prompt 把图片改成赛博朋克风格，霓虹灯，高对比\"");
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
     * 修复：移除模型不支持的参数，仅保留兼容的参数
     */
    private static String generateEditedImage(String apiKey, String dataUri, String prompt, String negativePrompt, int width, int height) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", IMAGE_EDIT_MODEL);
        payload.put("prompt", prompt);
        // 调整为模型兼容的参数值
        payload.put("num_inference_steps", 30);
        payload.put("cfg", 3.0);
        payload.put("image", dataUri);
        if (!isBlank(negativePrompt)) {
            payload.put("negative_prompt", negativePrompt);
        }
        // 仅保留分辨率参数（模型核心兼容）
        payload.put("width", width);
        payload.put("height", height);

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
                    hint = "（401 未授权：通常是 API Key 没填/填错/已失效，或 base_url 选错）";
                } else if (code == 500) {
                    hint = "（500 服务器错误：通常是参数不兼容或分辨率超限，已自动限制分辨率为" + MAX_RESOLUTION + "x" + MAX_RESOLUTION + "）";
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
            parent.mkdirs();
        }
        try (OutputStream out = new java.io.FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}