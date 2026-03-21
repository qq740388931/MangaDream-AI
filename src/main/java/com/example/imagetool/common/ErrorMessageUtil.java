package com.example.imagetool.common;

/**
 * 返回给前端的错误文案（截断，避免过长）
 */
public final class ErrorMessageUtil {

    private static final int MAX = 800;

    private ErrorMessageUtil() {
    }

    public static String fromThrowable(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return truncate(m.trim(), MAX);
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
