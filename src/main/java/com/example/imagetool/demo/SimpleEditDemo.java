package com.example.imagetool.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 带错误输出的版本（所有if都加花括号）
 */
public class SimpleEditDemo {

    private static final String API_KEY = "sk-phoiprcjjwyxleuhqebupeyesuvlryrmettptixgmrwqsniz";
    private static final String BASE_URL = "https://api.siliconflow.cn/v1";
    private static final String MODEL = "Qwen/Qwen-Image-Edit-2509";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        // === 你要修改的地方 ===
        String imagePath = "C:\\Users\\zzc\\Desktop\\DSCF8860.JPG";
        String prompt = "【人物区域严格保持不变】\n" +
                "原始图片中的人物（包括：脸型轮廓、眉眼间距、鼻梁高度、嘴唇形状、发色、刘海、发际线、黑色Jeep衬衫、黑色裤子、白色运动鞋、侧身站立姿态、右手轻抚额头的动作）必须100%保留，不允许任何修改。\n" +
                "\n" +
                "【仅修改背景和环境】\n" +
                "1. 背景：替换为郁郁葱葱的温室花园，有粉色玫瑰、蓝色绣球花、紫色薰衣草、白色牡丹\n" +
                "2. 新增元素：白色华丽铁艺长椅、半透明蕾丝窗帘搭在椅上、复古花卉地毯铺在木地板、带花纹茶杯放在椅上\n" +
                "3. 光影：柔和的漫射阳光从树叶间洒下，在人物和地面形成自然斑驳光影\n" +
                "\n" +
                "【整体风格】\n" +
                "- 色调：柔和的马卡龙色系（粉、蓝、紫、白）\n" +
                "- 氛围：梦幻空灵，柔焦效果\n" +
                "- 打光：电影感布光，人物受光均匀，背景有层次\n" +
                "\n" +
                "【融合要求】\n" +
                "- 人物和背景的光影方向必须完全一致，自然融合，不能有抠图感\n" +
                "- 背景适当虚化，突出人物主体\n" +
                "- 人物边缘与背景过渡自然，无生硬边界\n" +
                "\n" +
                "【画质】\n" +
                "8K超精细画质，专业摄影级细节";
        String outputPath = "D:\\图片\\output.png";
        // ====================

        // 1. 图片转base64
        File imageFile = new File(imagePath);
        if (!imageFile.exists()) {
            System.err.println("图片不存在: " + imagePath);
            return;
        }

        byte[] imageBytes = readAllBytes(imageFile);
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String mime = guessMimeType(imageFile.getName());
        String dataUri = "data:" + mime + ";base64," + b64;

        // 2. 构建请求参数
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", MODEL);
        payload.put("prompt", prompt);
        payload.put("image", dataUri);

        payload.put("num_inference_steps", 50);
        payload.put("cfg", 8.5);
        payload.put("strength", 0.55);
        payload.put("width", 1024);
        payload.put("height", 1024);

        String jsonBody = MAPPER.writeValueAsString(payload);
        System.out.println("请求参数: " + jsonBody);

        // 3. 调用API
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_URL + "/images/generations");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + API_KEY);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            System.out.println("响应代码: " + responseCode);

            // 读取响应（包括错误响应）
            String respText;
            if (responseCode >= 200 && responseCode < 300) {
                try (InputStream is = conn.getInputStream()) {
                    respText = new String(readAllBytes(is), StandardCharsets.UTF_8);
                }
                System.out.println("成功响应: " + respText);

                // 4. 解析返回的图片URL
                Map<String, Object> resp = MAPPER.readValue(respText, new TypeReference<Map<String, Object>>() {});
                List<Map<String, Object>> images = (List<Map<String, Object>>) resp.get("images");

                if (images != null && !images.isEmpty()) {
                    String imageUrl = (String) images.get(0).get("url");

                    // 5. 下载并保存
                    HttpURLConnection imgConn = (HttpURLConnection) new URL(imageUrl).openConnection();
                    BufferedImage img = ImageIO.read(imgConn.getInputStream());
                    imgConn.disconnect();

                    File outFile = new File(outputPath);
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    ImageIO.write(img, "PNG", outFile);

                    System.out.println("保存成功：" + outFile.getAbsolutePath());
                } else {
                    System.err.println("返回数据中没有图片: " + respText);
                }
            } else {
                try (InputStream es = conn.getErrorStream()) {
                    respText = new String(readAllBytes(es), StandardCharsets.UTF_8);
                }
                System.err.println("错误响应: " + respText);
                System.err.println("错误码: " + responseCode);
            }
        } catch (Exception e) {
            System.err.println("请求异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readAllBytes(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAllBytes(in);
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String guessMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "image/jpeg";
    }
}