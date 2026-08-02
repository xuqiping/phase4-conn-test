package com.superprogrammer.user.dto;

public record UserSummary(
        Long id,
        String email,
        String phone,
        String role,
        String status,
        Boolean emailVerified,
        Boolean phoneVerified,
        Integer deviceLimit,
        Integer offlineCacheMinutes
) {
}
