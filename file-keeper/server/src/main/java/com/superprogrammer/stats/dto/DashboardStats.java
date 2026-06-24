package com.superprogrammer.stats.dto;

public record DashboardStats(
        long totalUsers,
        long pendingReviewUsers,
        long activeUsers,
        long disabledUsers,
        long pendingVerificationUsers,
        long activeDevices,
        long expiringSoonEntitlements,
        long expiredEntitlements
) {
}
