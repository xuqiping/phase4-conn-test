package com.superprogrammer.device.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AnonymousTrialRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<AnonymousTrialRecord> findByDeviceId(String deviceId) {
        List<AnonymousTrialRecord> records = jdbcTemplate.query(
                "select id, device_id, fingerprint_hash, device_name, trial_started_at, trial_expires_at, free_module_code, free_module_selected_at, last_free_module_changed_at, status " +
                        "from anonymous_device_trials where device_id = ? and deleted = 0",
                trialMapper(),
                deviceId
        );
        return records.stream().findFirst();
    }

    public AnonymousTrialRecord insert(String deviceId, String fingerprintHash, String deviceName, OffsetDateTime trialStartedAt, OffsetDateTime trialExpiresAt) {
        jdbcTemplate.update(
                "insert into anonymous_device_trials (device_id, fingerprint_hash, device_name, trial_started_at, trial_expires_at, status, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, ?, 'active', 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                deviceId,
                fingerprintHash,
                deviceName,
                trialStartedAt,
                trialExpiresAt
        );
        return findByDeviceId(deviceId).orElseThrow();
    }

    public AnonymousTrialRecord updateFreeModule(String deviceId, String freeModuleCode, OffsetDateTime changedAt) {
        jdbcTemplate.update(
                "update anonymous_device_trials set free_module_code = ?, free_module_selected_at = coalesce(free_module_selected_at, ?), last_free_module_changed_at = ?, updated_at = CURRENT_TIMESTAMP where device_id = ? and deleted = 0",
                freeModuleCode,
                changedAt,
                changedAt,
                deviceId
        );
        return findByDeviceId(deviceId).orElseThrow();
    }

    private RowMapper<AnonymousTrialRecord> trialMapper() {
        return (rs, rowNum) -> new AnonymousTrialRecord(
                rs.getLong("id"),
                rs.getString("device_id"),
                rs.getString("fingerprint_hash"),
                rs.getString("device_name"),
                toOffsetDateTime(rs.getTimestamp("trial_started_at")),
                toOffsetDateTime(rs.getTimestamp("trial_expires_at")),
                rs.getString("free_module_code"),
                toOffsetDateTime(rs.getTimestamp("free_module_selected_at")),
                toOffsetDateTime(rs.getTimestamp("last_free_module_changed_at")),
                rs.getString("status")
        );
    }

    private OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts != null ? ts.toInstant().atOffset(OffsetDateTime.now().getOffset()) : null;
    }

    public record AnonymousTrialRecord(
            Long id,
            String deviceId,
            String fingerprintHash,
            String deviceName,
            OffsetDateTime trialStartedAt,
            OffsetDateTime trialExpiresAt,
            String freeModuleCode,
            OffsetDateTime freeModuleSelectedAt,
            OffsetDateTime lastFreeModuleChangedAt,
            String status
    ) {
    }
}
