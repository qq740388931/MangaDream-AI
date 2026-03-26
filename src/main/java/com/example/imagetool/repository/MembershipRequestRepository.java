package com.example.imagetool.repository;

import com.example.imagetool.entity.MembershipRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class MembershipRequestRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final RowMapper<MembershipRequest> ROW_MAPPER = new RowMapper<MembershipRequest>() {
        @Override
        public MembershipRequest mapRow(ResultSet rs, int rowNum) throws SQLException {
            MembershipRequest m = new MembershipRequest();
            m.setId(rs.getLong("id"));
            m.setUserId(rs.getObject("user_id") != null ? rs.getLong("user_id") : null);
            m.setUsername(rs.getString("username"));
            m.setEmail(rs.getString("email"));
            m.setPlanCode(rs.getString("plan_code"));
            m.setStatus(rs.getString("status"));
            m.setCreatedAt(rs.getString("created_at"));
            m.setUpdatedAt(rs.getString("updated_at"));
            m.setAdminComment(rs.getString("admin_comment"));
            m.setCreditedAt(rs.getString("credited_at"));
            return m;
        }
    };

    public MembershipRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Long userId, String username, String email, String planCode) {
        String now = LocalDateTime.now().format(F);
        String sql = "INSERT INTO membership_request (user_id, username, email, plan_code, status, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,?,?)";
        jdbcTemplate.update(sql, userId, username, email, planCode, "pending", now, now);
    }

    public MembershipRequest findById(Long id) {
        List<MembershipRequest> list = jdbcTemplate.query(
                "SELECT id, user_id, username, email, plan_code, status, created_at, updated_at, admin_comment, credited_at FROM membership_request WHERE id = ?",
                ROW_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 将 pending 标为已充值（原子抢占）；返回影响行数，0 表示已处理过或不存在。
     */
    public int markCreditedAtomic(Long id, String now, String adminComment) {
        String sql = "UPDATE membership_request SET status = ?, updated_at = ?, credited_at = ?, admin_comment = ? "
                + "WHERE id = ? AND status = ?";
        return jdbcTemplate.update(sql, "credited", now, now, adminComment, id, "pending");
    }

    public long countPending() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM membership_request WHERE status = ?", Long.class, "pending");
        return n != null ? n : 0L;
    }

    public long countWithFilter(String statusFilter) {
        if (statusFilter == null || "ALL".equalsIgnoreCase(statusFilter.trim())) {
            Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM membership_request", Long.class);
            return n != null ? n : 0L;
        }
        String t = statusFilter.trim().toLowerCase();
        if ("pending".equals(t)) {
            return countPending();
        }
        if ("credited".equals(t)) {
            Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM membership_request WHERE status = ?", Long.class, "credited");
            return n != null ? n : 0L;
        }
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM membership_request", Long.class);
        return n != null ? n : 0L;
    }

    public List<Map<String, Object>> listForAdmin(int offset, int limit, String statusFilter) {
        StringBuilder sql = new StringBuilder(
                "SELECT m.id, m.user_id, m.username, m.email, m.plan_code, m.status, m.created_at, m.updated_at, "
                        + "m.credited_at, m.admin_comment, u.points AS user_points "
                        + "FROM membership_request m LEFT JOIN users u ON u.id = m.user_id WHERE 1=1 ");
        List<Object> args = new ArrayList<Object>();
        if (statusFilter != null && !statusFilter.trim().isEmpty() && !"ALL".equalsIgnoreCase(statusFilter.trim())) {
            String t = statusFilter.trim().toLowerCase();
            if ("pending".equals(t) || "credited".equals(t)) {
                sql.append(" AND m.status = ?");
                args.add(t);
            }
        }
        sql.append(" ORDER BY m.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", rs.getLong("id"));
            row.put("userId", rs.getObject("user_id") != null ? rs.getLong("user_id") : null);
            row.put("username", rs.getString("username"));
            row.put("email", rs.getString("email"));
            row.put("planCode", rs.getString("plan_code"));
            row.put("status", rs.getString("status"));
            row.put("createdAt", rs.getString("created_at"));
            row.put("updatedAt", rs.getString("updated_at"));
            row.put("creditedAt", rs.getString("credited_at"));
            row.put("adminComment", rs.getString("admin_comment"));
            int pts = rs.getInt("user_points");
            row.put("userPoints", rs.wasNull() ? null : pts);
            return row;
        }, args.toArray());
    }
}
