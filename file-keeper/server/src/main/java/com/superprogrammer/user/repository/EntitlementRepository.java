package com.superprogrammer.user.repository;

import com.superprogrammer.user.dto.ModuleEntitlementDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class EntitlementRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<ModuleEntitlementDto> findByUserId(Long userId) {
        return jdbcTemplate.query(
                "select id, user_id, module_code, enabled, expires_at from user_module_entitlements where user_id = ? and deleted = 0 order by id",
                entitlementMapper(),
                userId
        );
    }

    public List<ModuleEntitlementDto> findActiveByUserId(Long userId) {
        return jdbcTemplate.query(
                "select id, user_id, module_code, enabled, expires_at from user_module_entitlements " +
                        "where user_id = ? and enabled = true and deleted = 0 and (expires_at is null or expires_at > CURRENT_TIMESTAMP) order by id",
                entitlementMapper(),
                userId
        );
    }

    public Optional<ModuleEntitlementDto> findById(Long entitlementId) {
        List<ModuleEntitlementDto> results = jdbcTemplate.query(
                "select id, user_id, module_code, enabled, expires_at from user_module_entitlements where id = ? and deleted = 0",
                entitlementMapper(),
                entitlementId
        );
        return results.stream().findFirst();
    }

    public boolean existsByUserAndModule(Long userId, String moduleCode) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from user_module_entitlements where user_id = ? and module_code = ? and deleted = 0",
                Integer.class, userId, moduleCode
        );
        return count != null && count > 0;
    }

    public ModuleEntitlementDto insert(Long userId, String moduleCode, OffsetDateTime expiresAt) {
        jdbcTemplate.update(
                "insert into user_module_entitlements (user_id, module_code, enabled, expires_at, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, true, ?, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                userId, moduleCode, expiresAt
        );
        return findByUserAndModule(userId, moduleCode).orElseThrow();
    }

    public ModuleEntitlementDto update(Long entitlementId, Boolean enabled, OffsetDateTime expiresAt) {
        if (enabled != null) {
            jdbcTemplate.update("update user_module_entitlements set enabled = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                    enabled, entitlementId);
        }
        if (expiresAt != null) {
            jdbcTemplate.update("update user_module_entitlements set expires_at = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                    expiresAt, entitlementId);
        }
        return findById(entitlementId).orElseThrow();
    }

    public void softDeleteById(Long entitlementId) {
        jdbcTemplate.update("update user_module_entitlements set deleted = 1, updated_at = CURRENT_TIMESTAMP where id = ?", entitlementId);
    }

    private Optional<ModuleEntitlementDto> findByUserAndModule(Long userId, String moduleCode) {
        List<ModuleEntitlementDto> results = jdbcTemplate.query(
                "select id, user_id, module_code, enabled, expires_at from user_module_entitlements where user_id = ? and module_code = ? and deleted = 0 order by id desc limit 1",
                entitlementMapper(), userId, moduleCode
        );
        return results.stream().findFirst();
    }

    private RowMapper<ModuleEntitlementDto> entitlementMapper() {
        return (rs, rowNum) -> new ModuleEntitlementDto(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("module_code"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant().atOffset(java.time.ZoneOffset.systemDefault().getRules().getOffset(java.time.Instant.now())) : null
        );
    }
}
