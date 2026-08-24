package com.superprogrammer.device.repository;

import com.superprogrammer.device.dto.DeviceDto;
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
public class DeviceRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<DeviceDto> findByUserId(Long userId) {
        return jdbcTemplate.query(
                "select id, user_id, device_id, fingerprint_hash, device_name, status, last_seen_at, time_sync_anomaly_count " +
                        "from user_devices where user_id = ? and deleted = 0 order by id",
                deviceMapper(), userId
        );
    }

    public Optional<DeviceDto> findByUserIdAndDeviceId(Long userId, String deviceId) {
        List<DeviceDto> results = jdbcTemplate.query(
                "select id, user_id, device_id, fingerprint_hash, device_name, status, last_seen_at, time_sync_anomaly_count " +
                        "from user_devices where user_id = ? and device_id = ? and deleted = 0",
                deviceMapper(), userId, deviceId
        );
        return results.stream().findFirst();
    }

    public DeviceDto insert(Long userId, String deviceId, String fingerprintHash, String deviceName) {
        jdbcTemplate.update(
                "insert into user_devices (user_id, device_id, fingerprint_hash, device_name, status, last_seen_at, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, 'active', CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                userId, deviceId, fingerprintHash, deviceName
        );
        return findByUserIdAndDeviceId(userId, deviceId).orElseThrow();
    }

    public void updateLastSeenAt(Long id) {
        jdbcTemplate.update(
                "update user_devices set last_seen_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP where id = ?",
                id
        );
    }

    public void updateStatus(Long id, String status, Long operatorId) {
        jdbcTemplate.update(
                "update user_devices set status = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                status, operatorId, id
        );
    }

    public void incrementTimeSyncAnomaly(Long id) {
        jdbcTemplate.update(
                "update user_devices set time_sync_anomaly_count = time_sync_anomaly_count + 1, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                id
        );
    }

    private RowMapper<DeviceDto> deviceMapper() {
        return (rs, rowNum) -> new DeviceDto(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("device_id"),
                rs.getString("fingerprint_hash"),
                rs.getString("device_name"),
                rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("last_seen_at")),
                rs.getInt("time_sync_anomaly_count")
        );
    }

    private OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts != null ? ts.toInstant().atOffset(OffsetDateTime.now().getOffset()) : null;
    }
}
