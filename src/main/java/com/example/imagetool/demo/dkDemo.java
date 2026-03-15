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
 * 优化版本：针对身份保持（人像不变）场景
 * 主要改进：
 * 1. 增加推理步骤到70，提升细节保留
 * 2. 提高CFG到6.5，平衡提示词跟随和原图保持
 * 3. 添加strength=0.65，控制修改强度
 * 4. 分辨率自动对齐8的倍数
 * 5. VLM提示优化，让人物描述更详细
 *
 * 编码规范：所有if语句都使用花括号 {}
 */
public class dkDemo {

    private static final String BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String VLM_MODEL = "Qwen/Qwen3-VL-32B-Instruct";
    private static final String IMAGE_EDIT_MODEL = "Qwen/Qwen-Image-Edit-2509";
    private static final String API_KEY = "sk-phoiprcjjwyxleuhqebupeyesuvlryrmettptixgmrwqsniz";
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
        System.out.println("=== 配置信息 ===");
        System.out.println("base_url=" + BASE_URL);
        System.out.println("vlm_model=" + VLM_MODEL);
        System.out.println("image_model=" + IMAGE_EDIT_MODEL);
        System.out.println("apiKey prefix=" + maskPrefix(apiKey) + "，len=" + apiKey.length());
        System.out.println("当前工作目录=" + new File(".").getAbsolutePath());

        File imageFile = new File(imagePath);
        if (!imageFile.exists() || !imageFile.isFile()) {
            System.err.println("图片不存在或不是文件：" + imagePath);
            System.exit(2);
        }

        // 获取并处理分辨率
        int[] imgSize = getImageSize(imageFile);
        int[] limitedSize = limitResolution(imgSize[0], imgSize[1]);
        int width = limitedSize[0];
        int height = limitedSize[1];
        System.out.println("\n=== 分辨率处理 ===");
        System.out.println("原图分辨率：" + imgSize[0] + "x" + imgSize[1]);
        System.out.println("处理后分辨率：" + width + "x" + height + "（已对齐8的倍数）");

        String mime = guessMimeType(imageFile.getName());
        byte[] imageBytes = readAllBytes(imageFile);
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUri = "data:" + mime + ";base64," + b64;

        // 优化：让VLM更详细地描述人物特征
        String instruction =
               /* "你是一个专业的图片编辑助手。用户会给你一张图片，要求修改背景和风格，但必须严格保留人物特征。\n\n" +
                        "第一步：请详细描述图片中人物的以下特征（越详细越好）：\n" +
                        "1. 面部特征：脸型、眉眼间距、鼻型、嘴唇形状、下巴轮廓\n" +
                        "2. 发型：发色、长度、刘海样式、发际线\n" +
                        "3. 服装：颜色、款式、材质、细节（如领口、袖口、图案）\n" +
                        "4. 姿态：身体朝向、手部动作\n\n" +
                        "第二步：基于以上描述和用户需求，生成用于图生图(img2img)的正向提示词。\n" +
                        "正向提示词必须包含：人物特征的详细描述 + 新场景描述 + 风格/光影/质量词。\n\n" +
                        "请输出JSON格式（不要输出多余文字）：\n" +
                        "{\n" +
                        "  \"人物特征描述\": \"第一步的详细描述\",\n" +
                        "  \"edited_prompt\": \"用于图生图的正向提示词，包含详细人物特征+新场景+风格+质量词\",\n" +
                        "  \"negative_prompt\": \"需要避免的元素，如低质量、模糊、变形等\",\n" +
                        "  \"steps\": [\"简短步骤1\", \"步骤2\", \"步骤3\"],\n" +
                        "  \"notes\": \"对强度/分辨率/构图等建议\"\n" +
                        "}\n\n" +*/
                        "用户需求如下：\n" + prompt;

        System.out.println("\n=== 调用VLM分析图片 ===");
        Map<String, Object> chatPayload = buildChatPayload(dataUri, instruction);
        String chatRespText = postJson(BASE_URL + "/chat/completions", apiKey, MAPPER.writeValueAsString(chatPayload));

        Map<String, Object> chatResp = MAPPER.readValue(chatRespText, new TypeReference<Map<String, Object>>() {});
        String content = extractContent(chatResp);

        System.out.println("VLM返回内容：");
        System.out.println(content);

        // 解析VLM返回的JSON
        String editedPrompt = null;
        String negativePrompt = null;
        String 人物特征 = null;
        try {
            Map<String, Object> contentJson = MAPPER.readValue(content, new TypeReference<Map<String, Object>>() {});
            Object ep = contentJson.get("edited_prompt");
            Object np = contentJson.get("negative_prompt");
            Object fp = contentJson.get("人物特征描述");
            if (ep != null) {
                editedPrompt = String.valueOf(ep);
            }
            if (np != null) {
                negativePrompt = String.valueOf(np);
            }
            if (fp != null) {
                人物特征 = String.valueOf(fp);
            }
        } catch (Exception ignore) {
            System.out.println("警告：VLM返回不是标准JSON格式，将使用原始提示词");
        }

        if (isBlank(editedPrompt)) {
            editedPrompt = prompt;
        }
        if (isBlank(negativePrompt)) {
            negativePrompt = "低质量, 模糊, 扭曲, 变形, 多余肢体, 错误的手指, 糟糕的比例, 不自然的面部, 年龄变化, 发型改变";
        }

        System.out.println("\n=== 提取的编辑提示 ===");
        if (人物特征 != null) {
            System.out.println("人物特征描述：" + 人物特征);
        }
        System.out.println("正向提示词：" + editedPrompt);
        System.out.println("负向提示词：" + negativePrompt);

        String outPath = null;
        if (isBlank(outputPath)) {
            outPath = defaultOutFile(imageFile).getAbsolutePath();
        } else {
            outPath = outputPath.trim();
        }

        System.out.println("\n=== 调用图像生成模型 ===");
        System.out.println("参数设置：");
        System.out.println("- steps: 70");
        System.out.println("- cfg: 6.5");
        System.out.println("- strength: 0.65");
        System.out.println("- 分辨率: " + width + "x" + height);

        String imageUrl = generateEditedImage(apiKey, dataUri, editedPrompt, negativePrompt, width, height);
        System.out.println("生成图片URL=" + imageUrl);

        long savedBytes = downloadAndSaveAsLossless(imageUrl, new File(outPath));
        System.out.println("\n=== 保存成功 ===");
        System.out.println("文件路径：" + new File(outPath).getAbsolutePath());
        System.out.println("文件大小：" + savedBytes + " bytes");
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

    // 将分辨率对齐到8的倍数
    private static int[] alignToMultiple(int width, int height, int multiple) {
        int newWidth = (width / multiple) * multiple;
        int newHeight = (height / multiple) * multiple;
        // 确保至少为512
        if (newWidth < 512) {
            newWidth = 512;
        }
        if (newHeight < 512) {
            newHeight = 512;
        }
        // 确保不超过最大限制
        if (newWidth > MAX_RESOLUTION) {
            newWidth = MAX_RESOLUTION;
        }
        if (newHeight > MAX_RESOLUTION) {
            newHeight = MAX_RESOLUTION;
        }
        return new int[]{newWidth, newHeight};
    }

    // 限制分辨率不超过模型上限（等比例缩放）并对齐8的倍数
    private static int[] limitResolution(int originalWidth, int originalHeight) {
        if (originalWidth <= MAX_RESOLUTION && originalHeight <= MAX_RESOLUTION) {
            return alignToMultiple(originalWidth, originalHeight, 8);
        }
        double ratio = Math.min((double) MAX_RESOLUTION / originalWidth, (double) MAX_RESOLUTION / originalHeight);
        int newWidth = (int) Math.round(originalWidth * ratio);
        int newHeight = (int) Math.round(originalHeight * ratio);
        return alignToMultiple(newWidth, newHeight, 8);
    }

    /**
     * 优化版本：针对身份保持调整参数
     */
    private static String generateEditedImage(String apiKey, String dataUri, String prompt, String negativePrompt, int width, int height) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", IMAGE_EDIT_MODEL);
        payload.put("prompt", prompt);

        // 关键优化1：增加推理步骤，提升细节保留
        payload.put("num_inference_steps", 50);

        // 关键优化2：提高CFG，平衡保持和编辑
        payload.put("cfg", 7.0);

        // 关键优化3：添加strength参数，控制修改程度
        // 0.65 适合换背景，既能改变场景又能保留人物特征
        payload.put("strength", 0.65);

        // 关键优化4：使用对齐后的分辨率
        payload.put("width", width);
        payload.put("height", height);

        payload.put("image", dataUri);
        if (!isBlank(negativePrompt)) {
            payload.put("negative_prompt", negativePrompt);
        }

        // 可选：固定种子让结果可复现（调试用）
        // payload.put("seed", 42);

        String respText = postJson(BASE_URL + "/images/generations", apiKey, MAPPER.writeValueAsString(payload));
        Map<String, Object> resp = MAPPER.readValue(respText, new TypeReference<Map<String, Object>>() {});
        return extractFirstImageUrl(resp);
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
        System.err.println("用法：");
        System.err.println("  --image <图片路径> --prompt <提示词>");
        System.err.println();
        System.err.println("示例：");
        System.err.println("  java doubaoDemo --image D:\\test.png --prompt \"把背景换成海滩\"");
        System.exit(2);
    }

    private static boolean isBlank(String s) {
        if (s == null) {
            return true;
        }
        if (s.trim().length() == 0) {
            return true;
        }
        return false;
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
            InputStream is = null;
            if (code >= 200 && code < 300) {
                is = conn.getInputStream();
            } else {
                is = conn.getErrorStream();
            }
            String resp = readToString(is);
            if (code < 200 || code >= 300) {
                String hint = "";
                if (code == 401) {
                    hint = "（401 未授权：API Key无效）";
                } else if (code == 500) {
                    hint = "（500 服务器错误：参数不兼容，已自动优化参数）";
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
        if (contentObj == null) {
            return "";
        }
        return String.valueOf(contentObj);
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
}