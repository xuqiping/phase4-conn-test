package com.superprogrammer.stats.repository;

import com.superprogrammer.stats.dto.DashboardStats;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StatsRepository {

    private final JdbcTemplate jdbcTemplate;

    public DashboardStats loadDashboard() {
        long total = count("select count(*) from users where deleted = 0");
        long active = count("select count(*) from users where deleted = 0 and status = 'active'");
        long disabled = count("select count(*) from users where deleted = 0 and status = 'disabled'");
        long activeDevices = count("select count(*) from user_devices where deleted = 0 and status = 'active'");
        return new DashboardStats(total, active, disabled, activeDevices);
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value != null ? value : 0L;
    }
}
