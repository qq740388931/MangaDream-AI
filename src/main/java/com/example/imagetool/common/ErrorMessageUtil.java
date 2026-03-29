package com.example.imagetool.common;

/**
 * 返回给前端的错误文案（截断，避免过长）
 */
public final class ErrorMessageUtil {

    private static final int MAX = 800;

    /** 对外统一提示：避免把超时、堆栈等英文信息直接暴露给前端 */
    public static final String USER_BUSY = "系统繁忙，请稍后再试";

    private ErrorMessageUtil() {
    }

    /**
     * 给前端展示的通用错误文案（网络超时、第三方异常等统一用此句，避免泄露内部细节）。
     */
    public static String userFacing(Throwable e) {
        return USER_BUSY;
    }

    /**
     * 供服务端日志、{@code generate_log} 等落库：串联异常类型与 message（含 cause 链），不给前端直出。
     */
    public static String detailForLog(Throwable e) {
        if (e == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        int depth = 0;
        while (t != null && depth < 8) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            String m = t.getMessage();
            if (m != null && !m.trim().isEmpty()) {
                sb.append(": ").append(m.trim());
            }
            t = t.getCause();
            depth++;
        }
        return truncate(sb.toString(), MAX);
    }

    public static String fromThrowable(Throwable e) {
        if (e == null) {
            return "Unknown error";
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
