package com.superprogrammer.settings.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SystemSettingRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<String> findValue(String key) {
        List<String> values = jdbcTemplate.query(
                "select setting_value from system_settings where setting_key = ? and deleted = 0",
                (rs, rowNum) -> rs.getString("setting_value"),
                key
        );
        return values.stream().findFirst();
    }

    public long countByKey(String key) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from system_settings where setting_key = ? and deleted = 0",
                Long.class, key
        );
        return count != null ? count : 0L;
    }

    /**
     * 两步法 upsert（兼容 H2 测试库与 PostgreSQL 生产库，不依赖 ON CONFLICT 方言）。
     */
    public void upsert(String key, String value, String description, Long adminUserId) {
        if (countByKey(key) > 0) {
            jdbcTemplate.update(
                    "update system_settings set setting_value = ?, description = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP "
                            + "where setting_key = ? and deleted = 0",
                    value, description, adminUserId, key
            );
        } else {
            jdbcTemplate.update(
                    "insert into system_settings (setting_key, setting_value, description, created_by, created_at, updated_by, updated_at, deleted) "
                            + "values (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                    key, value, description, adminUserId, adminUserId
            );
        }
    }
}
