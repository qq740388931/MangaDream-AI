package com.example.imagetool.controller;

import com.example.imagetool.service.MembershipAdminService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 内部 SQLite 表浏览与行编辑：需先登录（Session），密码不写在代码里；
 * 默认使用配置中的 SHA-256 十六进制校验（对应常见测试密码），也可用环境变量明文覆盖。
 */
@RestController
@RequestMapping("/internal/db-browser")
public class DbBrowserController {

    private static final String SESSION_FLAG = "MANGADREAM_DB_BROWSER_AUTH";

    private final JdbcTemplate jdbcTemplate;
    private final MembershipAdminService membershipAdminService;

    /** 非空时优先：与输入做常量时间比较（适合环境变量注入） */
    @Value("${mangadream.db-browser.password:}")
    private String configuredPlainPassword;

    /** 密码的 SHA-256（十六进制小写）；明文未配置时使用（配置键须用 passwordSha256，避免连字符被 Spring 解析为减法） */
    @Value("${mangadream.db-browser.passwordSha256:b67aa86914f3ac65cd8fddbfb60e89462d8a48046e2f9df9679986bd717555dd}")
    private String configuredPasswordSha256Hex;

    public DbBrowserController(JdbcTemplate jdbcTemplate, MembershipAdminService membershipAdminService) {
        this.jdbcTemplate = jdbcTemplate;
        this.membershipAdminService = membershipAdminService;
    }

    @GetMapping(produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> page(HttpSession session) {
        checkFeatureEnabled();
        if (!isAuthenticated(session)) {
            return readHtml("internal/db-browser-login.html");
        }
        return readHtml("internal/db-browser.html");
    }

    /** 后台：API 访问日志页 */
    @GetMapping(value = "/access-logs", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> accessLogsPage(HttpSession session) {
        checkFeatureEnabled();
        if (!isAuthenticated(session)) {
            return readHtml("internal/db-browser-login.html");
        }
        return readHtml("internal/access-log.html");
    }

    /** 后台：登录语义日志 login_audit_log（首访、会话恢复、Google/开发 注册与登录） */
    @GetMapping(value = "/login-audit-logs", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> loginAuditLogsPage(HttpSession session) {
        checkFeatureEnabled();
        if (!isAuthenticated(session)) {
            return readHtml("internal/db-browser-login.html");
        }
        return readHtml("internal/login-audit.html");
    }

    /** 后台：生成记录 generate_log（关联 users 显示用户名） */
    @GetMapping(value = "/generate-logs", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> generateLogsPage(HttpSession session) {
        checkFeatureEnabled();
        if (!isAuthenticated(session)) {
            return readHtml("internal/db-browser-login.html");
        }
        return readHtml("internal/generate-log.html");
    }

    /** 后台：会员充值申请（PayPal 邮件提交）— 待处理 / 已充值 */
    @GetMapping(value = "/membership-requests", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> membershipRequestsPage(HttpSession session) {
        checkFeatureEnabled();
        if (!isAuthenticated(session)) {
            return readHtml("internal/db-browser-login.html");
        }
        return readHtml("internal/membership-requests.html");
    }

    /** 后台：全局异常等写入的 app_error_log */
    @GetMapping(value = "/error-logs", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> errorLogsPage(HttpSession session) {
        checkFeatureEnabled();
        if (!isAuthenticated(session)) {
            return readHtml("internal/db-browser-login.html");
        }
        return readHtml("internal/error-log.html");
    }

    /** 待处理条数（导航角标） */
    @GetMapping("/api/membership-requests/pending-count")
    public Map<String, Object> membershipPendingCount(HttpSession session) {
        checkFeatureEnabled();
        requireAuth(session);
        return membershipAdminService.pendingCountMap();
    }

    /** 分页查询 membership_request */
    @GetMapping("/api/membership-requests")
    public Map<String, Object> listMembershipRequests(
            HttpSession session,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "status", defaultValue = "ALL") String status) {
        checkFeatureEnabled();
        requireAuth(session);
        return membershipAdminService.listMembershipRequests(page, size, status);
    }

    /** 确认已收款：为用户充值 50 积分并标记已充 */
    @PostMapping("/api/membership-requests/{id}/credit")
    public Map<String, Object> creditMembershipRequest(HttpSession session, @PathVariable("id") long id) {
        checkFeatureEnabled();
        requireAuth(session);
        return membershipAdminService.creditTrial(id);
    }

    /**
     * 分页查询 generate_log，LEFT JOIN users 取 name、email。
     * type：ALL、style、random；q：在 reason、history_id、type、用户 name/email 上模糊匹配。
     */
    @GetMapping("/api/generate-logs")
    public Map<String, Object> listGenerateLogs(
            HttpSession session,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "type", defaultValue = "ALL") String typeFilter,
            @RequestParam(value = "q", required = false) String q) {
        checkFeatureEnabled();
        requireAuth(session);
        if (page < 0) {
            page = 0;
        }
        size = Math.min(Math.max(size, 1), 200);

        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<Object>();
        appendGenerateLogTypeFilter(where, args, typeFilter);
        appendGenerateLogKeywordFilter(where, args, q);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM generate_log g LEFT JOIN users u ON g.user_id = u.id WHERE 1=1" + where,
                Long.class,
                args.toArray());
        if (total == null) {
            total = 0L;
        }

        int offset = page * size;
        List<Object> pageArgs = new ArrayList<Object>(args);
        pageArgs.add(size);
        pageArgs.add(offset);

        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT g.id, g.user_id, g.type, g.ip, g.user_agent, g.success, g.reason, g.history_id, g.result_url, "
                        + "g.request_json, g.response_json, g.created_at, "
                        + "u.name AS user_name, u.email AS user_email "
                        + "FROM generate_log g "
                        + "LEFT JOIN users u ON g.user_id = u.id "
                        + "WHERE 1=1"
                        + where
                        + " ORDER BY g.id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getObject("id"));
                    m.put("userId", rs.getObject("user_id"));
                    m.put("userName", rs.getString("user_name"));
                    m.put("userEmail", rs.getString("user_email"));
                    m.put("type", rs.getString("type"));
                    m.put("success", rs.getObject("success"));
                    m.put("reason", rs.getString("reason"));
                    m.put("historyId", rs.getString("history_id"));
                    m.put("resultUrl", rs.getString("result_url"));
                    m.put("requestJson", rs.getString("request_json"));
                    m.put("responseJson", rs.getString("response_json"));
                    m.put("createdAt", rs.getString("created_at"));
                    String ua = rs.getString("user_agent");
                    m.put("userAgent", ua != null && ua.length() > 200 ? ua.substring(0, 200) + "…" : ua);
                    return m;
                },
                pageArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        out.put("rows", rows);
        out.put("types", GENERATE_LOG_TYPES);
        return out;
    }

    private static final List<Map<String, String>> GENERATE_LOG_TYPES = Arrays.asList(
            typeOpt("ALL", "全部"),
            typeOpt("style", "按风格生成"),
            typeOpt("random", "随机生成"));

    private static void appendGenerateLogTypeFilter(StringBuilder where, List<Object> args, String typeFilter) {
        if (typeFilter == null || typeFilter.trim().isEmpty() || "ALL".equalsIgnoreCase(typeFilter.trim())) {
            return;
        }
        String t = typeFilter.trim().toLowerCase(Locale.ROOT);
        if ("style".equals(t) || "random".equals(t)) {
            where.append(" AND g.type = ?");
            args.add(t);
        }
    }

    private static void appendGenerateLogKeywordFilter(StringBuilder where, List<Object> args, String q) {
        if (q == null || q.trim().isEmpty()) {
            return;
        }
        String like = "%" + q.trim() + "%";
        where.append(
                " AND (g.reason LIKE ? OR IFNULL(g.history_id,'') LIKE ? OR g.type LIKE ? OR IFNULL(u.name,'') LIKE ? OR IFNULL(u.email,'') LIKE ? OR IFNULL(g.ip,'') LIKE ?)");
        args.add(like);
        args.add(like);
        args.add(like);
        args.add(like);
        args.add(like);
        args.add(like);
    }

    /**
     * 分页查询 api_access_log。type：ALL、LOGIN、AUTH_CONFIG、RANDOM、GENERATE、INSPIRATION、
     * MEMBERSHIP、FEEDBACK、STYLE、RAPHAEL_HISTORY、GENERATE_RESULT、OTHER。
     * q：在 path、user_email、ip 上做模糊匹配。
     */
    @GetMapping("/api/access-logs")
    public Map<String, Object> listAccessLogs(
            HttpSession session,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "type", defaultValue = "ALL") String type,
            @RequestParam(value = "q", required = false) String q) {
        checkFeatureEnabled();
        requireAuth(session);
        if (page < 0) {
            page = 0;
        }
        size = Math.min(Math.max(size, 1), 200);

        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<Object>();
        appendAccessLogTypeFilter(where, args, type);
        appendAccessLogKeywordFilter(where, args, q);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_access_log WHERE 1=1" + where, Long.class, args.toArray());
        if (total == null) {
            total = 0L;
        }

        int offset = page * size;
        List<Object> pageArgs = new ArrayList<Object>(args);
        pageArgs.add(size);
        pageArgs.add(offset);

        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT id, user_id, user_email, path, method, query_string, ip, user_agent, "
                        + "http_status, duration_ms, created_at FROM api_access_log WHERE 1=1"
                        + where
                        + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getObject("id"));
                    m.put("userId", rs.getObject("user_id"));
                    m.put("userEmail", rs.getString("user_email"));
                    m.put("path", rs.getString("path"));
                    m.put("method", rs.getString("method"));
                    m.put("queryString", rs.getString("query_string"));
                    m.put("ip", rs.getString("ip"));
                    String ua = rs.getString("user_agent");
                    m.put("userAgent", ua != null && ua.length() > 160 ? ua.substring(0, 160) + "…" : ua);
                    m.put("httpStatus", rs.getObject("http_status"));
                    m.put("durationMs", rs.getObject("duration_ms"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                },
                pageArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        out.put("rows", rows);
        out.put("types", ACCESS_LOG_TYPES);
        return out;
    }

    /**
     * 分页查询 app_error_log（全局异常处理器写入）。
     * status：ALL、400、500；q：在 path、exception_class、message、top_class、ip 上模糊匹配。
     */
    @GetMapping("/api/error-logs")
    public Map<String, Object> listErrorLogs(
            HttpSession session,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "status", defaultValue = "ALL") String statusFilter,
            @RequestParam(value = "q", required = false) String q) {
        checkFeatureEnabled();
        requireAuth(session);
        if (page < 0) {
            page = 0;
        }
        size = Math.min(Math.max(size, 1), 200);

        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<Object>();
        appendErrorLogStatusFilter(where, args, statusFilter);
        appendErrorLogKeywordFilter(where, args, q);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_error_log WHERE 1=1" + where, Long.class, args.toArray());
        if (total == null) {
            total = 0L;
        }

        int offset = page * size;
        List<Object> pageArgs = new ArrayList<Object>(args);
        pageArgs.add(size);
        pageArgs.add(offset);

        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT id, http_status, path, method, query_string, ip, user_agent, "
                        + "exception_class, message, top_class, top_method, top_file, top_line, stack_trace, created_at "
                        + "FROM app_error_log WHERE 1=1"
                        + where
                        + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getObject("id"));
                    m.put("httpStatus", rs.getObject("http_status"));
                    m.put("path", rs.getString("path"));
                    m.put("method", rs.getString("method"));
                    m.put("queryString", rs.getString("query_string"));
                    m.put("ip", rs.getString("ip"));
                    String ua = rs.getString("user_agent");
                    m.put("userAgent", ua != null && ua.length() > 160 ? ua.substring(0, 160) + "…" : ua);
                    m.put("exceptionClass", rs.getString("exception_class"));
                    m.put("message", rs.getString("message"));
                    m.put("topClass", rs.getString("top_class"));
                    m.put("topMethod", rs.getString("top_method"));
                    m.put("topFile", rs.getString("top_file"));
                    m.put("topLine", rs.getObject("top_line"));
                    m.put("stackTrace", rs.getString("stack_trace"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                },
                pageArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        out.put("rows", rows);
        out.put("statusOptions", ERROR_LOG_STATUS_OPTIONS);
        return out;
    }

    private static final List<Map<String, String>> ERROR_LOG_STATUS_OPTIONS = Arrays.asList(
            statusOpt("ALL", "全部"),
            statusOpt("400", "400"),
            statusOpt("500", "500"));

    private static Map<String, String> statusOpt(String id, String label) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("id", id);
        m.put("label", label);
        return m;
    }

    private static void appendErrorLogStatusFilter(StringBuilder where, List<Object> args, String statusFilter) {
        if (statusFilter == null || statusFilter.trim().isEmpty() || "ALL".equalsIgnoreCase(statusFilter.trim())) {
            return;
        }
        String s = statusFilter.trim();
        if ("400".equals(s) || "500".equals(s)) {
            try {
                int code = Integer.parseInt(s);
                where.append(" AND http_status = ?");
                args.add(code);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static void appendErrorLogKeywordFilter(StringBuilder where, List<Object> args, String q) {
        if (q == null || q.trim().isEmpty()) {
            return;
        }
        String like = "%" + q.trim() + "%";
        where.append(
                " AND (IFNULL(path,'') LIKE ? OR IFNULL(exception_class,'') LIKE ? OR IFNULL(message,'') LIKE ? "
                        + "OR IFNULL(top_class,'') LIKE ? OR IFNULL(top_method,'') LIKE ? OR IFNULL(ip,'') LIKE ?)");
        args.add(like);
        args.add(like);
        args.add(like);
        args.add(like);
        args.add(like);
        args.add(like);
    }

    /**
     * 分页查询 login_audit_log（语义事件，非每条 HTTP）。
     * type：ALL、FIRST_URL_ENTRY、TOKEN_RESUME、GOOGLE_SIGNUP、GOOGLE_LOGIN、DEV_SIGNUP、DEV_LOGIN。
     * q：在 event_type、user_email、ip、user_id 上模糊匹配。
     */
    @GetMapping("/api/login-audit-logs")
    public Map<String, Object> listLoginAuditLogs(
            HttpSession session,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "type", defaultValue = "ALL") String type,
            @RequestParam(value = "q", required = false) String q) {
        checkFeatureEnabled();
        requireAuth(session);
        if (page < 0) {
            page = 0;
        }
        size = Math.min(Math.max(size, 1), 200);

        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<Object>();
        appendLoginAuditTypeFilter(where, args, type);
        appendLoginAuditKeywordFilter(where, args, q);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM login_audit_log WHERE 1=1" + where, Long.class, args.toArray());
        if (total == null) {
            total = 0L;
        }

        int offset = page * size;
        List<Object> pageArgs = new ArrayList<Object>(args);
        pageArgs.add(size);
        pageArgs.add(offset);

        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT id, event_type, user_id, user_email, ip, user_agent, created_at FROM login_audit_log WHERE 1=1"
                        + where
                        + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", rs.getObject("id"));
                    m.put("eventType", rs.getString("event_type"));
                    m.put("userId", rs.getObject("user_id"));
                    m.put("userEmail", rs.getString("user_email"));
                    m.put("ip", rs.getString("ip"));
                    String ua = rs.getString("user_agent");
                    m.put("userAgent", ua != null && ua.length() > 160 ? ua.substring(0, 160) + "…" : ua);
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                },
                pageArgs.toArray());

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        out.put("rows", rows);
        out.put("types", LOGIN_AUDIT_TYPES);
        return out;
    }

    private static final List<Map<String, String>> ACCESS_LOG_TYPES = Arrays.asList(
            typeOpt("ALL", "全部"),
            typeOpt("LOGIN", "登录与会话（Google / 开发登录 / 进入与恢复）"),
            typeOpt("AUTH_CONFIG", "拉取登录配置 /api/auth/config"),
            typeOpt("RANDOM", "随机生成 /api/generate-random"),
            typeOpt("GENERATE", "指定风格生成 /api/generate"),
            typeOpt("INSPIRATION", "灵感列表 /api/inspiration"),
            typeOpt("MEMBERSHIP", "会员申请 /api/membership-request"),
            typeOpt("FEEDBACK", "反馈 /api/feedback"),
            typeOpt("STYLE", "风格 API /api/v1/…"),
            typeOpt("RAPHAEL_HISTORY", "任务历史 /api/raphael-history/…"),
            typeOpt("GENERATE_RESULT", "生成结果回写 /api/generate-log/result"),
            typeOpt("OTHER", "其它"));

    private static Map<String, String> typeOpt(String id, String label) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("id", id);
        m.put("label", label);
        return m;
    }

    private static void appendAccessLogTypeFilter(StringBuilder where, List<Object> args, String type) {
        if (type == null || type.trim().isEmpty() || "ALL".equalsIgnoreCase(type.trim())) {
            return;
        }
        String t = type.trim().toUpperCase(Locale.ROOT);
        switch (t) {
            case "LOGIN":
                where.append(" AND (path = ? OR path = ? OR path = ?)");
                args.add("/api/auth/google");
                args.add("/api/auth/dev-login");
                args.add("/api/audit/entry");
                break;
            case "AUTH_CONFIG":
                where.append(" AND path = ?");
                args.add("/api/auth/config");
                break;
            case "RANDOM":
                where.append(" AND path = ?");
                args.add("/api/generate-random");
                break;
            case "GENERATE":
                where.append(" AND path = ?");
                args.add("/api/generate");
                break;
            case "INSPIRATION":
                where.append(" AND path = ?");
                args.add("/api/inspiration");
                break;
            case "MEMBERSHIP":
                where.append(" AND path = ?");
                args.add("/api/membership-request");
                break;
            case "FEEDBACK":
                where.append(" AND path = ?");
                args.add("/api/feedback");
                break;
            case "STYLE":
                where.append(" AND path LIKE ?");
                args.add("/api/v1/%");
                break;
            case "RAPHAEL_HISTORY":
                where.append(" AND path LIKE ?");
                args.add("/api/raphael-history/%");
                break;
            case "GENERATE_RESULT":
                where.append(" AND path LIKE ?");
                args.add("/api/generate-log/result%");
                break;
            case "OTHER":
                where.append(
                        " AND NOT ("
                                + "path IN (?,?,?,?,?,?,?) "
                                + "OR path LIKE ? OR path LIKE ? OR path LIKE ?"
                                + ")");
                args.add("/api/auth/google");
                args.add("/api/auth/dev-login");
                args.add("/api/auth/config");
                args.add("/api/generate-random");
                args.add("/api/generate");
                args.add("/api/inspiration");
                args.add("/api/membership-request");
                args.add("/api/feedback");
                args.add("/api/v1/%");
                args.add("/api/raphael-history/%");
                args.add("/api/generate-log/result%");
                break;
            default:
                break;
        }
    }

    private static void appendAccessLogKeywordFilter(StringBuilder where, List<Object> args, String q) {
        if (q == null || q.trim().isEmpty()) {
            return;
        }
        String like = "%" + q.trim() + "%";
        where.append(" AND (path LIKE ? OR IFNULL(user_email,'') LIKE ? OR IFNULL(ip,'') LIKE ?)");
        args.add(like);
        args.add(like);
        args.add(like);
    }

    private static final List<Map<String, String>> LOGIN_AUDIT_TYPES = Arrays.asList(
            typeOpt("ALL", "全部"),
            typeOpt("FIRST_URL_ENTRY", "匿名首次进入（未登录打开带 entry-audit 的页面）"),
            typeOpt("TOKEN_RESUME", "已登录·会话恢复"),
            typeOpt("GOOGLE_SIGNUP", "新用户·Google 首次注册"),
            typeOpt("GOOGLE_LOGIN", "老用户·Google 登录"),
            typeOpt("DEV_SIGNUP", "新用户·开发登录"),
            typeOpt("DEV_LOGIN", "老用户·开发登录"));

    private static void appendLoginAuditTypeFilter(StringBuilder where, List<Object> args, String type) {
        if (type == null || type.trim().isEmpty() || "ALL".equalsIgnoreCase(type.trim())) {
            return;
        }
        String t = type.trim();
        String upper = t.toUpperCase(Locale.ROOT);
        switch (upper) {
            case "FIRST_URL_ENTRY":
            case "TOKEN_RESUME":
            case "GOOGLE_SIGNUP":
            case "GOOGLE_LOGIN":
            case "DEV_SIGNUP":
            case "DEV_LOGIN":
                where.append(" AND event_type = ?");
                args.add(t);
                break;
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid type");
        }
    }

    private static void appendLoginAuditKeywordFilter(StringBuilder where, List<Object> args, String q) {
        if (q == null || q.trim().isEmpty()) {
            return;
        }
        String like = "%" + q.trim() + "%";
        where.append(" AND (IFNULL(event_type,'') LIKE ? OR IFNULL(user_email,'') LIKE ? OR IFNULL(ip,'') LIKE ? OR IFNULL(CAST(user_id AS TEXT),'') LIKE ?)");
        args.add(like);
        args.add(like);
        args.add(like);
        args.add(like);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, String> body, HttpSession session) {
        checkFeatureEnabled();
        String pwd = body == null ? null : body.get("password");
        if (!verifyPassword(pwd)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        session.setAttribute(SESSION_FLAG, Boolean.TRUE);
        return Collections.singletonMap("ok", true);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        checkFeatureEnabled();
        session.invalidate();
        return Collections.singletonMap("ok", true);
    }

    @GetMapping("/api/tables")
    public List<String> listTables(HttpSession session) {
        checkFeatureEnabled();
        requireAuth(session);
        return jdbcTemplate.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                (rs, rowNum) -> rs.getString(1));
    }

    @GetMapping("/api/table/{table}")
    public Map<String, Object> readTable(
            @PathVariable("table") String table,
            HttpSession session,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        checkFeatureEnabled();
        requireAuth(session);
        validateTableName(table);
        assertTableExists(table);
        if (page < 0) {
            page = 0;
        }
        size = Math.min(Math.max(size, 1), 200);

        List<Map<String, Object>> colInfos = jdbcTemplate.query(
                "PRAGMA table_info(\"" + escapeIdent(table) + "\")",
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("name", rs.getString("name"));
                    m.put("type", rs.getString("type"));
                    m.put("pk", rs.getInt("pk"));
                    m.put("notnull", rs.getInt("notnull"));
                    return m;
                });
        List<String> columnNames = colInfos.stream()
                .map(m -> (String) m.get("name"))
                .collect(Collectors.toList());

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + escapeIdent(table) + "\"", Long.class);
        int offset = page * size;

        List<Map<String, String>> rows = jdbcTemplate.query(
                "SELECT rowid AS _rowid_, * FROM \"" + escapeIdent(table) + "\" LIMIT ? OFFSET ?",
                (rs, rowNum) -> rowToStringMap(rs, columnNames),
                size, offset);

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("table", table);
        out.put("columns", colInfos);
        out.put("columnNames", columnNames);
        out.put("rows", rows);
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    @PostMapping("/api/update-row")
    public Map<String, Object> updateRow(HttpSession session, @RequestBody UpdateRowRequest body) {
        checkFeatureEnabled();
        requireAuth(session);
        if (body == null || body.table == null || body.table.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing table");
        }
        String table = body.table.trim();
        validateTableName(table);
        assertTableExists(table);
        if (body.rowid == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing rowid");
        }
        if (body.values == null || body.values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing values");
        }

        Set<String> allowed = new LinkedHashSet<String>(getDataColumnNames(table));
        List<String> setCols = new ArrayList<String>();
        List<Object> args = new ArrayList<Object>();
        for (Map.Entry<String, String> e : body.values.entrySet()) {
            String col = e.getKey();
            if ("_rowid_".equalsIgnoreCase(col) || "rowid".equalsIgnoreCase(col)) {
                continue;
            }
            if (!allowed.contains(col)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown column: " + col);
            }
            setCols.add("\"" + escapeIdent(col) + "\" = ?");
            args.add(e.getValue());
        }
        if (setCols.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No columns to update");
        }

        String sql = "UPDATE \"" + escapeIdent(table) + "\" SET "
                + String.join(", ", setCols)
                + " WHERE rowid = ?";
        args.add(body.rowid);
        int n = jdbcTemplate.update(sql, args.toArray());
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("updated", n);
        return out;
    }

    /** 按 rowid 删除一行（查=分页浏览，改=保存，删=本接口） */
    @PostMapping("/api/delete-row")
    public Map<String, Object> deleteRow(HttpSession session, @RequestBody DeleteRowRequest body) {
        checkFeatureEnabled();
        requireAuth(session);
        if (body == null || body.table == null || body.table.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing table");
        }
        if (body.rowid == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing rowid");
        }
        String table = body.table.trim();
        validateTableName(table);
        assertTableExists(table);
        int n = jdbcTemplate.update("DELETE FROM \"" + escapeIdent(table) + "\" WHERE rowid = ?", body.rowid);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("deleted", n);
        return out;
    }

    /** 插入一行：仅允许表内列名；空字符串的字段不写入（由 SQLite 默认 NULL/自增） */
    @PostMapping("/api/insert-row")
    public Map<String, Object> insertRow(HttpSession session, @RequestBody InsertRowRequest body) {
        checkFeatureEnabled();
        requireAuth(session);
        if (body == null || body.table == null || body.table.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing table");
        }
        if (body.values == null || body.values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing values");
        }
        String table = body.table.trim();
        validateTableName(table);
        assertTableExists(table);
        Set<String> allowed = new LinkedHashSet<String>(getDataColumnNames(table));
        List<String> insertCols = new ArrayList<String>();
        List<Object> args = new ArrayList<Object>();
        for (Map.Entry<String, String> e : body.values.entrySet()) {
            String col = e.getKey();
            if ("_rowid_".equalsIgnoreCase(col) || "rowid".equalsIgnoreCase(col)) {
                continue;
            }
            if (!allowed.contains(col)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown column: " + col);
            }
            String v = e.getValue();
            if (v == null || v.trim().isEmpty()) {
                continue;
            }
            insertCols.add("\"" + escapeIdent(col) + "\"");
            args.add(v.trim());
        }
        if (insertCols.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one non-empty column value required");
        }
        String placeholders = String.join(",", Collections.nCopies(insertCols.size(), "?"));
        String sql = "INSERT INTO \"" + escapeIdent(table) + "\" ("
                + String.join(", ", insertCols)
                + ") VALUES ("
                + placeholders
                + ")";
        int n = jdbcTemplate.update(sql, args.toArray());
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("inserted", n);
        try {
            Long rid = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
            out.put("lastInsertRowid", rid);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 清空表内全部数据（DELETE，不删表结构） */
    @PostMapping("/api/clear-table")
    public Map<String, Object> clearTable(HttpSession session, @RequestBody Map<String, String> body) {
        checkFeatureEnabled();
        requireAuth(session);
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing body");
        }
        String table = body.get("table");
        if (table == null || table.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing table");
        }
        table = table.trim();
        validateTableName(table);
        assertTableExists(table);
        int n = jdbcTemplate.update("DELETE FROM \"" + escapeIdent(table) + "\"");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("deleted", n);
        return out;
    }

    /**
     * 执行单条 SQL（SELECT/PRAGMA 返回结果集最多 500 行；INSERT/UPDATE/DELETE/DDL 返回影响行数）。
     * 危险操作仅限已登录后台，请谨慎使用。
     */
    @PostMapping("/api/sql")
    public Map<String, Object> executeSql(HttpSession session, @RequestBody Map<String, String> body) {
        checkFeatureEnabled();
        requireAuth(session);
        String sql = body == null ? null : body.get("sql");
        if (sql == null || sql.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty sql");
        }
        final String sqlExec = sql.trim();
        if (sqlExec.length() > 100_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sql too long (max 100000)");
        }
        try {
            return jdbcTemplate.execute((ConnectionCallback<Map<String, Object>>) connection -> {
                try (Statement st = connection.createStatement()) {
                    boolean hasResultSet = st.execute(sqlExec);
                    Map<String, Object> out = new LinkedHashMap<String, Object>();
                    if (hasResultSet) {
                        try (ResultSet rs = st.getResultSet()) {
                            ResultSetMetaData md = rs.getMetaData();
                            int cc = md.getColumnCount();
                            List<String> colNames = new ArrayList<String>();
                            for (int i = 1; i <= cc; i++) {
                                colNames.add(md.getColumnLabel(i));
                            }
                            List<List<Object>> rows = new ArrayList<List<Object>>();
                            final int maxRows = 500;
                            boolean truncated = false;
                            while (rs.next()) {
                                if (rows.size() >= maxRows) {
                                    truncated = true;
                                    break;
                                }
                                List<Object> row = new ArrayList<Object>();
                                for (int i = 1; i <= cc; i++) {
                                    row.add(sqlCellToJsonSafe(rs.getObject(i)));
                                }
                                rows.add(row);
                            }
                            out.put("kind", "resultSet");
                            out.put("columns", colNames);
                            out.put("rows", rows);
                            out.put("truncated", truncated);
                            if (truncated) {
                                out.put("hint", "最多返回 " + maxRows + " 行，请加 LIMIT 缩小结果");
                            }
                            return out;
                        }
                    } else {
                        int uc = st.getUpdateCount();
                        out.put("kind", "update");
                        out.put("updateCount", uc);
                        return out;
                    }
                }
            });
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        }
    }

    private static Object sqlCellToJsonSafe(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) {
                sb.append(String.format(Locale.ROOT, "%02x", x));
            }
            return "[blob hex] " + sb;
        }
        return v;
    }

    public static class UpdateRowRequest {
        public String table;
        public Long rowid;
        public Map<String, String> values;
    }

    public static class DeleteRowRequest {
        public String table;
        public Long rowid;
    }

    public static class InsertRowRequest {
        public String table;
        public Map<String, String> values;
    }

    private ResponseEntity<String> readHtml(String classpath) {
        try {
            ClassPathResource res = new ClassPathResource(classpath);
            String html = StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to load page");
        }
    }

    private void checkFeatureEnabled() {
        boolean plain = configuredPlainPassword != null && !configuredPlainPassword.trim().isEmpty();
        boolean hash = configuredPasswordSha256Hex != null && !configuredPasswordSha256Hex.trim().isEmpty();
        if (!plain && !hash) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static boolean isAuthenticated(HttpSession session) {
        return session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_FLAG));
    }

    private void requireAuth(HttpSession session) {
        if (!isAuthenticated(session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private boolean verifyPassword(String input) {
        if (input == null) {
            return false;
        }
        if (configuredPlainPassword != null && !configuredPlainPassword.trim().isEmpty()) {
            return constantTimeEqualsUtf8(configuredPlainPassword.trim(), input);
        }
        String hex = configuredPasswordSha256Hex == null ? "" : configuredPasswordSha256Hex.trim().toLowerCase(Locale.ROOT);
        if (hex.isEmpty()) {
            return false;
        }
        byte[] expected = hexToBytes(hex);
        if (expected == null) {
            return false;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] actual = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(actual, expected);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEqualsUtf8(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        return MessageDigest.isEqual(x, y);
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return null;
        }
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        try {
            for (int i = 0; i < n; i++) {
                int j = i * 2;
                out[i] = (byte) Integer.parseInt(hex.substring(j, j + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static void validateTableName(String name) {
        if (name == null || !name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid table name");
        }
    }

    private void assertTableExists(String table) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name = ?",
                Integer.class,
                table);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found");
        }
    }

    private List<String> getDataColumnNames(String table) {
        return jdbcTemplate.query(
                "PRAGMA table_info(\"" + escapeIdent(table) + "\")",
                (rs, rowNum) -> rs.getString("name"));
    }

    private static String escapeIdent(String ident) {
        return ident.replace("\"", "\"\"");
    }

    private Map<String, String> rowToStringMap(ResultSet rs, List<String> dataColumns) throws SQLException {
        Map<String, String> row = new LinkedHashMap<String, String>();
        long rowid = rs.getLong("_rowid_");
        row.put("_rowid_", String.valueOf(rowid));
        for (String col : dataColumns) {
            Object v = rs.getObject(col);
            row.put(col, valueToDisplayString(rs, col, v));
        }
        return row;
    }

    private String valueToDisplayString(ResultSet rs, String col, Object v) throws SQLException {
        if (v == null) {
            return "";
        }
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) {
                sb.append(String.format(Locale.ROOT, "%02x", x));
            }
            return "[blob hex] " + sb;
        }
        if (v instanceof Double || v instanceof Float) {
            return rs.getString(col);
        }
        return String.valueOf(v);
    }
}
