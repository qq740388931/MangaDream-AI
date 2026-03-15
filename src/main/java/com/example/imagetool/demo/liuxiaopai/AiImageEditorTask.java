package com.example.imagetool.demo.liuxiaopai;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AiImageEditorTask {

    private static final String API_BASE_URL = "https://raphael.app/api";

    private static final String COOKIE_HEADER = "g_state={\"i_l\":0,\"i_ll\":1772897887793,\"i_b\":\"bQ4rfZwJzPmwr9N1ujAfMT0XuQ38Vyv2jJmCzEGKUbQ\",\"i_e\":{\"enable_itp_optimization\":0}}; __Secure-better-auth.session_token=oRUShEmp7wnOk8hmze7TOFJ1LEaqVooc.HMTtj%2BUslZxyWfVoSIYMGL7frsgnYf%2BXZMKwVigFJHQ%3D; cf_clearance=F3PsyiufjlgO8bp1is36QypyF.1WqT9IErIJdfLMkLo-1773228695-1.2.1.1-_Gv1gDr.vh2SeusLas6uJOaAr3VhReW62L25PuDx73r5HYqk4PtTDAdqeS8gNdxV4F691I.xqTACn4XANu6sYE9DbsrkGgiNN6IGLsobylsUdjpmOyXKxRCNGsh8QxNDNJjI4ZwhD_OYWZwb.sz2ArJosLdgBLaIWKaeQ2ssIZB8yIHtsu.3TEEL5uSJf7CZc3BzgdtTI2b1esMP8idI8WSNEEM8FcxhSBttJAl_aik";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static long DEFAULT_POLL_INTERVAL_MS = 2000;
    private static final int MAX_POLL_TIMES = 100;

    public static void main(String[] args) {
        try {
            TaskSubmitResponse submitResponse = submitTask();
            System.out.println("任务提交成功，historyId: " + submitResponse.historyId);
            System.out.println("轮询间隔: " + submitResponse.pollIntervalMs + "ms");

            String outputImageUrl = pollTaskResult(submitResponse.historyId, submitResponse.pollIntervalMs);
            if (outputImageUrl == null) {
                System.err.println("任务超时，未获取到结果");
                return;
            }
            System.out.println("任务完成，生成图片URL: " + outputImageUrl);

            downloadImage(outputImageUrl, "generated_image.webp");
            System.out.println("图片已下载到本地：generated_image.webp");

        } catch (Exception e) {
            System.err.println("执行异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static TaskSubmitResponse submitTask() throws Exception {
        String submitUrl = API_BASE_URL + "/ai-image-editor/task";
        HttpURLConnection connection = null;
        BufferedReader reader = null;

        try {
            String requestBody = "{\n" +
                    "    \"prompt\": \"Create a dramatic black-and-white headshot of the subject or subjects with a moody, cinematic atmosphere. Use high-contrast lighting that carves out the face with deep shadows and bright highlights. Make the subject appear wet, as if the subject has just been caught in the rain, with irregular water droplets and streaks across the cheeks, forehead, and jawline. Hair should look damp and slightly clumped, with a few strands falling naturally across the face. Keep the background dark and minimal so the illuminated features and droplets stand out. The overall look should feel intense, emotional, and photographic — a raw, expressive portrait with real rain texture and dramatic tonal depth.\",\n" +
                    "    \"input_image\": \"1111111111\",\n" +
                    "    \"input_image_mime_type\": \"image/webp\",\n" +
                    "    \"input_image_extension\": \"webp\",\n" +
                    "    \"width\": 683,\n" +
                    "    \"height\": 1024,\n" +
                    "    \"mode\": \"standard\",\n" +
                    "    \"client_request_id\": \"362374ee-11a9-484f-8ab4-b2cd0341a55f\"\n" +
                    "}";

            URL url = new URL(submitUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Cookie", COOKIE_HEADER);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);

            DataOutputStream out = new DataOutputStream(connection.getOutputStream());
            out.write(requestBody.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("提交任务失败，响应码: " + responseCode);
            }

            reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder responseBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseBuilder.append(line);
            }
            String responseJson = responseBuilder.toString();

            String historyId = extractValueFromJson(responseJson, "\"historyId\":\"([^\"]+)\"");
            String pollIntervalStr = extractValueFromJson(responseJson, "\"pollIntervalMs\":(\\d+)");
            long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
            if (pollIntervalStr != null) {
                pollIntervalMs = Long.parseLong(pollIntervalStr);
            }

            return new TaskSubmitResponse(historyId, pollIntervalMs);

        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String pollTaskResult(String historyId, long pollIntervalMs) throws Exception {
        String queryUrl = API_BASE_URL + "/history/" + historyId;
        int pollCount = 0;

        while (pollCount < MAX_POLL_TIMES) {
            pollCount++;
            System.out.println("第" + pollCount + "次轮询，查询任务状态...");

            HttpURLConnection connection = null;
            BufferedReader reader = null;

            try {
                URL url = new URL(queryUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Cookie", COOKIE_HEADER);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(30000);

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    throw new Exception("查询任务状态失败，响应码: " + responseCode);
                }

                reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder responseBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }
                String responseJson = responseBuilder.toString();

                String status = extractValueFromJson(responseJson, "\"status\":\"([^\"]+)\"");
                if ("processing".equals(status)) {
                    System.out.println("任务处理中，等待" + pollIntervalMs + "ms");
                    try {
                        Thread.sleep(pollIntervalMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new Exception("轮询被中断", e);
                    }
                    continue;
                } else if ("completed".equals(status)) {
                    String outputUrl = extractValueFromJson(responseJson, "\"url\":\"([^\"]+output\\.webp)\"");
                    if (outputUrl != null) {
                        return outputUrl;
                    } else {
                        throw new Exception("任务完成但未找到output图片");
                    }
                } else {
                    throw new Exception("任务异常，状态：" + status);
                }

            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return null;
    }

    private static void downloadImage(String imageUrl, String savePath) throws Exception {
        HttpURLConnection connection = null;
        InputStream in = null;
        OutputStream out = null;

        try {
            URL url = new URL(imageUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);

            in = connection.getInputStream();
            out = new FileOutputStream(savePath);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }

        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String extractValueFromJson(String json, String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    static class TaskSubmitResponse {
        String historyId;
        long pollIntervalMs;

        public TaskSubmitResponse(String historyId, long pollIntervalMs) {
            this.historyId = historyId;
            this.pollIntervalMs = pollIntervalMs;
        }

        public String getHistoryId() {
            return historyId;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }
    }
}