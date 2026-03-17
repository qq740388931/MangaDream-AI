package com.example.imagetool.repository;

import com.example.imagetool.entity.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final RowMapper<User> ROW_MAPPER = new RowMapper<User>() {
        @Override
        public User mapRow(ResultSet rs, int rowNum) throws SQLException {
            User u = new User();
            u.setId(rs.getLong("id"));
            u.setGoogleSub(rs.getString("google_sub"));
            u.setEmail(rs.getString("email"));
            u.setName(rs.getString("name"));
            u.setAvatarUrl(rs.getString("avatar_url"));
            u.setCreatedAt(rs.getString("created_at"));
            u.setLastLoginAt(rs.getString("last_login_at"));
            int pts = rs.getInt("points");
            u.setPoints(rs.wasNull() ? null : pts);
             int vip = rs.getInt("is_vip");
             u.setIsVip(rs.wasNull() ? null : vip);
             u.setVipExpireAt(rs.getString("vip_expire_at"));
            return u;
        }
    };

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public User findByGoogleSub(String sub) {
        String sql = "SELECT id, google_sub, email, name, avatar_url, created_at, last_login_at, points, is_vip, vip_expire_at FROM users WHERE google_sub = ?";
        List<User> list = jdbcTemplate.query(sql, ROW_MAPPER, sub);
        return list.isEmpty() ? null : list.get(0);
    }

    public User findById(Long id) {
        String sql = "SELECT id, google_sub, email, name, avatar_url, created_at, last_login_at, points, is_vip, vip_expire_at FROM users WHERE id = ?";
        List<User> list = jdbcTemplate.query(sql, ROW_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public User insert(String sub, String email, String name, String avatarUrl) {
        String now = LocalDateTime.now().format(FORMATTER);
        String sql = "INSERT INTO users (google_sub, email, name, avatar_url, created_at, last_login_at, points, is_vip, vip_expire_at) VALUES (?,?,?,?,?,?,?,?,?)";
        jdbcTemplate.update(sql, sub, email, name, avatarUrl, now, now, 10, 0, null);
        Long id = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
        return findById(id);
    }

    public void updateLoginInfo(Long id, String email, String name, String avatarUrl) {
        String now = LocalDateTime.now().format(FORMATTER);
        String sql = "UPDATE users SET email = ?, name = ?, avatar_url = ?, last_login_at = ? WHERE id = ?";
        jdbcTemplate.update(sql, email, name, avatarUrl, now, id);
    }

    public boolean deductPoints(Long userId, int amount) {
        String sql = "UPDATE users SET points = points - ? WHERE id = ? AND (points IS NOT NULL AND points >= ?)";
        int updated = jdbcTemplate.update(sql, amount, userId, amount);
        return updated > 0;
    }

    public String createSessionToken(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String now = LocalDateTime.now().format(FORMATTER);
        String sql = "INSERT INTO user_session (token, user_id, created_at, last_used_at) VALUES (?,?,?,?)";
        jdbcTemplate.update(sql, token, userId, now, now);
        return token;
    }

    public Long findUserIdByToken(String token) {
        String sql = "SELECT user_id FROM user_session WHERE token = ?";
        List<Long> list = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("user_id"), token);
        if (list.isEmpty()) {
            return null;
        }
        String now = LocalDateTime.now().format(FORMATTER);
        jdbcTemplate.update("UPDATE user_session SET last_used_at = ? WHERE token = ?", now, token);
        return list.get(0);
    }
}

