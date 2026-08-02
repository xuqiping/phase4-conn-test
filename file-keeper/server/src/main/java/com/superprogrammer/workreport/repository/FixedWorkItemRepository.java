package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.FixedWorkItem;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class FixedWorkItemRepository {

    private final JdbcTemplate jdbcTemplate;

    public FixedWorkItem insert(FixedWorkItem item) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into fixed_work_items (user_id, content, description, recurrence_type, reminder_time, reminder_days, timezone, reminder_enabled, legacy_push_platform, legacy_push_target_id, legacy_push_credential, push_target_id, sort_order, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[] { "id" }
            );
            ps.setLong(1, item.getUserId());
            ps.setString(2, item.getContent());
            ps.setString(3, item.getDescription());
            ps.setString(4, item.getRecurrenceType());
            ps.setTime(5, timeValue(item.getReminderTime()));
            ps.setString(6, item.getReminderDays());
            ps.setString(7, item.getTimezone());
            ps.setBoolean(8, item.getReminderEnabled() != null && item.getReminderEnabled());
            ps.setString(9, item.getLegacyPushPlatform());
            ps.setString(10, item.getLegacyPushTargetId());
            ps.setString(11, item.getLegacyPushCredential());
            ps.setObject(12, item.getPushTargetId());
            ps.setInt(13, item.getSortOrder() == null ? 0 : item.getSortOrder());
            ps.setObject(14, item.getCreatedBy());
            ps.setObject(15, item.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "固定工作保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "固定工作保存后无法查询"));
    }

    public FixedWorkItem update(FixedWorkItem item) {
        int rows = jdbcTemplate.update(
                "update fixed_work_items set content = ?, description = ?, recurrence_type = ?, reminder_time = ?, reminder_days = ?, timezone = ?, reminder_enabled = ?, legacy_push_platform = ?, legacy_push_target_id = ?, legacy_push_credential = ?, push_target_id = ?, sort_order = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                item.getContent(), item.getDescription(), item.getRecurrenceType(),
                timeValue(item.getReminderTime()), item.getReminderDays(), item.getTimezone(),
                item.getReminderEnabled(), item.getLegacyPushPlatform(), item.getLegacyPushTargetId(), item.getLegacyPushCredential(),
                item.getPushTargetId(), item.getSortOrder(), item.getUpdatedBy(), item.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "固定工作不存在");
        }
        return findById(item.getId()).orElseThrow();
    }

    public Optional<FixedWorkItem> findById(Long id) {
        List<FixedWorkItem> results = jdbcTemplate.query(
                "select id, user_id, content, description, recurrence_type, reminder_time, reminder_days, timezone, reminder_enabled, legacy_push_platform, legacy_push_target_id, legacy_push_credential, push_target_id, sort_order, created_by, created_at, updated_by, updated_at, deleted " +
                        "from fixed_work_items where id = ? and deleted = 0",
                itemMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<FixedWorkItem> findByUserIdAndType(Long userId, String recurrenceType) {
        return jdbcTemplate.query(
                "select id, user_id, content, description, recurrence_type, reminder_time, reminder_days, timezone, reminder_enabled, legacy_push_platform, legacy_push_target_id, legacy_push_credential, push_target_id, sort_order, created_by, created_at, updated_by, updated_at, deleted " +
                        "from fixed_work_items where user_id = ? and recurrence_type = ? and deleted = 0 order by sort_order asc, id asc",
                itemMapper(), userId, recurrenceType
        );
    }

    public List<FixedWorkItem> findByUserId(Long userId) {
        return jdbcTemplate.query(
                "select id, user_id, content, description, recurrence_type, reminder_time, reminder_days, timezone, reminder_enabled, legacy_push_platform, legacy_push_target_id, legacy_push_credential, push_target_id, sort_order, created_by, created_at, updated_by, updated_at, deleted " +
                        "from fixed_work_items where user_id = ? and deleted = 0 order by sort_order asc, id asc",
                itemMapper(), userId
        );
    }

    public List<FixedWorkItem> findEnabledReminders() {
        return jdbcTemplate.query(
                "select id, user_id, content, description, recurrence_type, reminder_time, reminder_days, timezone, reminder_enabled, legacy_push_platform, legacy_push_target_id, legacy_push_credential, push_target_id, sort_order, created_by, created_at, updated_by, updated_at, deleted " +
                        "from fixed_work_items where reminder_enabled = true and deleted = 0 order by id asc",
                itemMapper()
        );
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update fixed_work_items set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    private RowMapper<FixedWorkItem> itemMapper() {
        return (rs, rowNum) -> mapItem(rs);
    }

    private FixedWorkItem mapItem(ResultSet rs) throws SQLException {
        FixedWorkItem item = new FixedWorkItem();
        item.setId(rs.getLong("id"));
        item.setUserId(rs.getLong("user_id"));
        item.setContent(rs.getString("content"));
        item.setDescription(rs.getString("description"));
        item.setRecurrenceType(rs.getString("recurrence_type"));
        Time reminderTime = rs.getTime("reminder_time");
        item.setReminderTime(reminderTime != null ? reminderTime.toLocalTime() : null);
        item.setReminderDays(rs.getString("reminder_days"));
        item.setTimezone(rs.getString("timezone"));
        item.setReminderEnabled(rs.getBoolean("reminder_enabled"));
        item.setLegacyPushPlatform(rs.getString("legacy_push_platform"));
        item.setLegacyPushTargetId(rs.getString("legacy_push_target_id"));
        item.setLegacyPushCredential(rs.getString("legacy_push_credential"));
        item.setPushTargetId(rs.getObject("push_target_id", Long.class));
        item.setSortOrder(rs.getInt("sort_order"));
        item.setCreatedBy(rs.getObject("created_by", Long.class));
        item.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        item.setUpdatedBy(rs.getObject("updated_by", Long.class));
        item.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        item.setDeleted(rs.getInt("deleted"));
        return item;
    }

    private Time timeValue(LocalTime time) {
        return time == null ? null : Time.valueOf(time);
    }
}
