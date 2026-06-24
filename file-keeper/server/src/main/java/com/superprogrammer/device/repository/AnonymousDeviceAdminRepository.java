package com.superprogrammer.device.repository;

import com.superprogrammer.common.PageResult;
import com.superprogrammer.device.dto.AnonymousDeviceDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class AnonymousDeviceAdminRepository {

    private final JdbcTemplate jdbcTemplate;

    public PageResult<AnonymousDeviceDto> findAll(String status, Long minResetCount, String firstSeenIp, long page, long size) {
        StringBuilder countSql = new StringBuilder("select count(*) from anonymous_device_trials where deleted = 0");
        StringBuilder dataSql = new StringBuilder(
                "select id, device_id, fingerprint_hash, device_name, status, trial_started_at, trial_expires_at, " +
                        "free_module_code, free_module_selected_at, last_free_module_changed_at, created_at, updated_at, " +
                        "first_seen_ip, user_agent_hash, trial_reset_count " +
                        "from anonymous_device_trials where deleted = 0"
        );
        List<Object> params = new ArrayList<>();

        if (status != null && !status.isBlank()) {
            countSql.append(" and status = ?");
            dataSql.append(" and status = ?");
            params.add(status);
        }
        if (minResetCount != null && minResetCount > 0) {
            countSql.append(" and trial_reset_count >= ?");
            dataSql.append(" and trial_reset_count >= ?");
            params.add(minResetCount);
        }
        if (firstSeenIp != null && !firstSeenIp.isBlank()) {
            countSql.append(" and first_seen_ip = ?");
            dataSql.append(" and first_seen_ip = ?");
            params.add(firstSeenIp);
        }

        dataSql.append(" order by updated_at desc limit ? offset ?");

        long total = jdbcTemplate.queryForObject(countSql.toString(), Long.class, params.toArray());
        long offset = (page - 1) * size;
        params.add(size);
        params.add(offset);

        List<AnonymousDeviceDto> records = jdbcTemplate.query(dataSql.toString(), deviceMapper(), params.toArray());
        return new PageResult<>(records, total, page, size);
    }

    public AnonymousDeviceDto findByDeviceId(String deviceId) {
        List<AnonymousDeviceDto> records = jdbcTemplate.query(
                "select id, device_id, fingerprint_hash, device_name, status, trial_started_at, trial_expires_at, " +
                        "free_module_code, free_module_selected_at, last_free_module_changed_at, created_at, updated_at, " +
                        "first_seen_ip, user_agent_hash, trial_reset_count " +
                        "from anonymous_device_trials where device_id = ? and deleted = 0",
                deviceMapper(),
                deviceId
        );
        return records.stream().findFirst().orElse(null);
    }

    public int resetTrial(String deviceId, OffsetDateTime newExpiresAt, Long adminUserId) {
        return jdbcTemplate.update(
                "update anonymous_device_trials set trial_expires_at = ?, trial_started_at = CURRENT_TIMESTAMP, " +
                        "trial_reset_count = trial_reset_count + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ? " +
                        "where device_id = ? and deleted = 0",
                newExpiresAt,
                adminUserId,
                deviceId
        );
    }

    public List<IpDeviceCount> countDevicesByIp(int minCount) {
        return jdbcTemplate.query(
                "select first_seen_ip, count(*) as device_count from anonymous_device_trials " +
                        "where deleted = 0 group by first_seen_ip having count(*) >= ? order by device_count desc",
                (rs, rowNum) -> new IpDeviceCount(rs.getString("first_seen_ip"), rs.getInt("device_count")),
                minCount
        );
    }

    public record IpDeviceCount(String firstSeenIp, int deviceCount) {
    }

    public int updateStatus(String deviceId, String status, Long adminUserId) {
        return jdbcTemplate.update(
                "update anonymous_device_trials set status = ?, updated_at = CURRENT_TIMESTAMP, updated_by = ? " +
                        "where device_id = ? and deleted = 0",
                status,
                adminUserId,
                deviceId
        );
    }

    private RowMapper<AnonymousDeviceDto> deviceMapper() {
        return (rs, rowNum) -> new AnonymousDeviceDto(
                rs.getLong("id"),
                rs.getString("device_id"),
                rs.getString("fingerprint_hash"),
                rs.getString("device_name"),
                rs.getString("status"),
                toOffsetDateTime(rs.getTimestamp("trial_started_at")),
                toOffsetDateTime(rs.getTimestamp("trial_expires_at")),
                rs.getString("free_module_code"),
                toOffsetDateTime(rs.getTimestamp("free_module_selected_at")),
                toOffsetDateTime(rs.getTimestamp("last_free_module_changed_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at")),
                rs.getString("first_seen_ip"),
                rs.getString("user_agent_hash"),
                rs.getInt("trial_reset_count")
        );
    }

    private OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts != null ? ts.toInstant().atOffset(OffsetDateTime.now().getOffset()) : null;
    }
}
