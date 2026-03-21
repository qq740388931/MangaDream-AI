package com.example.imagetool.util;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 审计日志用：去掉超长 base64、截断，避免撑爆数据库与日志。
 */
public final class AuditLogBodySanitizer {

    private static final int MAX_LEN = 8000;
    /** imageBase64 / input_image 的值一般为单行，不含未转义引号 */
    private static final Pattern LARGE_JSON_FIELDS = Pattern.compile(
            "\"(imageBase64|input_image)\"\\s*:\\s*\"[^\"]*\"");

    private AuditLogBodySanitizer() {
    }

    public static String truncate(String s) {
        if (s == null) {
            return null;
        }
        if (s.length() <= MAX_LEN) {
            return s;
        }
        return s.substring(0, MAX_LEN) + "…(truncated totalLen=" + s.length() + ")";
    }

    /**
     * 将 JSON 中的 imageBase64 / input_image 等长字段替换为占位说明。
     */
    public static String maskLargeDataFields(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        if (json.length() > 500_000) {
            return "[json too large to mask, len=" + json.length() + "]";
        }
        String s = LARGE_JSON_FIELDS.matcher(json).replaceAll("\"$1\":\"[omitted]\"");
        return truncate(s);
    }

    public static String bytesToUtf8(byte[] buf) {
        if (buf == null || buf.length == 0) {
            return "";
        }
        return new String(buf, StandardCharsets.UTF_8);
    }
}
