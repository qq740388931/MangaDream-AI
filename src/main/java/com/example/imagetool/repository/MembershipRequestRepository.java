package com.example.imagetool.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Repository
public class MembershipRequestRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public MembershipRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Long userId, String username, String email, String planCode) {
        String now = LocalDateTime.now().format(F);
        String sql = "INSERT INTO membership_request (user_id, username, email, plan_code, status, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,?,?)";
        jdbcTemplate.update(sql, userId, username, email, planCode, "pending", now, now);
    }
}

