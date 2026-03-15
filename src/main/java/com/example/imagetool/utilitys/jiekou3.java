package com.example.imagetool.utilitys;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class jiekou3 {

    private static final String API_BASE_URL = "https://raphael.app/api";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static long DEFAULT_POLL_INTERVAL_MS = 2000;
    private static final int MAX_POLL_TIMES = 10;


    public static void getusdas(String historyId, long pollIntervalMs, String cookie) {

        try {
            String outputImageUrl = pollTaskResult(historyId, pollIntervalMs, cookie);
            if (outputImageUrl == null) {
                System.err.println("任务超时，未获取到结果");
                return;
            }
            System.out.println("任务完成，生成图片URL: " + outputImageUrl);

            //downloadImage(outputImageUrl, "generated_image.webp");
            //System.out.println("图片已下载到本地：generated_image.webp");

        } catch (Exception e) {
            System.err.println("执行异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String pollTaskResult(String historyId, long pollIntervalMs, String cookie) throws Exception {
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
                connection.setRequestProperty("Cookie", cookie);
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
                // 打印完整响应，方便后续调试
                System.out.println("接口2响应JSON: " + responseJson);

                String status = extractValueFromJson(responseJson, "\"status\":\"([^\"]+)\"");
                // 核心修复：兼容 "complete" 和 "completed" 两种状态
                if ("processing".equals(status)) {
                    System.out.println("任务处理中，等待" + pollIntervalMs + "ms");
                    Thread.sleep(pollIntervalMs);
                    continue;
                } else if ("complete".equals(status) || "completed".equals(status)) {
                    String outputUrl = extractValueFromJson(responseJson, "\"url\":\"([^\"]+output\\.webp)\"");
                    if (outputUrl != null) {
                        return outputUrl;
                    } else {
                        throw new Exception("任务完成但未找到output图片URL");
                    }
                } else {
                    throw new Exception("任务异常，状态：" + status);
                }

            } finally {
                if (reader != null) {
                    reader.close();
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
                in.close();
            }
            if (out != null) {
                out.close();
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
    }
}
