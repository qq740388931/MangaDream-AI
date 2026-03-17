package com.example.imagetool.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Repository
public class FeedbackRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public FeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String username, String email, String content) {
        String now = LocalDateTime.now().format(F);
        String sql = "INSERT INTO feedback (username, email, content, created_at) VALUES (?,?,?,?)";
        jdbcTemplate.update(sql, username, email, content, now);
    }
}

