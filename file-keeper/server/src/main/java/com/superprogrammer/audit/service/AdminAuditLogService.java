package com.superprogrammer.audit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminAuditLogService {

    private final JdbcTemplate jdbcTemplate;

    public void record(Long adminUserId, String action, String targetType, String targetId, String detail) {
        jdbcTemplate.update(
                "insert into admin_audit_logs (admin_user_id, action, target_type, target_id, detail, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                adminUserId,
                action,
                targetType,
                targetId,
                detail,
                adminUserId,
                adminUserId
        );
    }
}
