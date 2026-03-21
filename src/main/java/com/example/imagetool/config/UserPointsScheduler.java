package com.example.imagetool.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 用户积分定时任务：
 * - 每天凌晨 6 点执行一次
 * - 对积分小于 4 或为空的用户，将积分设置为 4
 * - 积分大于等于 4 的用户不变
 */
@Component
public class UserPointsScheduler {

    private final JdbcTemplate jdbcTemplate;

    public UserPointsScheduler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "0 0 6 * * ?")
    public void resetDailyPoints() {
        String sql = "UPDATE users SET points = 4 WHERE points IS NULL OR points < 4";
        jdbcTemplate.update(sql);
    }
}

