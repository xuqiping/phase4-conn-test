package com.superprogrammer.stats.dto;

public record DashboardStats(
        long totalUsers,
        long activeUsers,
        long disabledUsers,
        long activeDevices
) {
}
