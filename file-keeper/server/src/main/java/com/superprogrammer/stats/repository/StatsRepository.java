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
        long pendingReview = count("select count(*) from users where deleted = 0 and status = 'pending_review'");
        long active = count("select count(*) from users where deleted = 0 and status = 'active'");
        long disabled = count("select count(*) from users where deleted = 0 and status = 'disabled'");
        long pendingVerification = count("select count(*) from users where deleted = 0 and status = 'pending_verification'");

        long activeDevices = count("select count(*) from user_devices where deleted = 0 and status = 'active'");

        long expiringSoon = count(
                "select count(*) from user_module_entitlements " +
                        "where deleted = 0 and enabled = true " +
                        "and expires_at is not null " +
                        "and expires_at > CURRENT_TIMESTAMP " +
                        "and expires_at <= CURRENT_TIMESTAMP + INTERVAL '7 days'");

        long expired = count(
                "select count(*) from user_module_entitlements " +
                        "where deleted = 0 and enabled = true " +
                        "and expires_at is not null " +
                        "and expires_at < CURRENT_TIMESTAMP");

        return new DashboardStats(
                total, pendingReview, active, disabled, pendingVerification,
                activeDevices, expiringSoon, expired
        );
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value != null ? value : 0L;
    }
}
