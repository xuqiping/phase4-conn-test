package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.FuturePlan;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class FuturePlanRepository {

    private final JdbcTemplate jdbcTemplate;

    public FuturePlan insert(FuturePlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into future_plans (user_id, content, description, scheduled_at, timezone, reminder_enabled, reminder_minutes_before, push_platform, push_target_id, push_credential, status, sort_order, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[] { "id" }
            );
            ps.setLong(1, plan.getUserId());
            ps.setString(2, plan.getContent());
            ps.setString(3, plan.getDescription());
            ps.setTimestamp(4, Timestamp.from(plan.getScheduledAt().toInstant()));
            ps.setString(5, plan.getTimezone());
            ps.setBoolean(6, plan.getReminderEnabled() != null && plan.getReminderEnabled());
            ps.setInt(7, plan.getReminderMinutesBefore() == null ? 0 : plan.getReminderMinutesBefore());
            ps.setString(8, plan.getPushPlatform());
            ps.setString(9, plan.getPushTargetId());
            ps.setString(10, plan.getPushCredential());
            ps.setString(11, plan.getStatus() == null ? "PENDING" : plan.getStatus());
            ps.setInt(12, plan.getSortOrder() == null ? 0 : plan.getSortOrder());
            ps.setObject(13, plan.getCreatedBy());
            ps.setObject(14, plan.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "未来计划保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "未来计划保存后无法查询"));
    }

    public FuturePlan update(FuturePlan plan) {
        int rows = jdbcTemplate.update(
                "update future_plans set content = ?, description = ?, scheduled_at = ?, timezone = ?, reminder_enabled = ?, reminder_minutes_before = ?, push_platform = ?, push_target_id = ?, push_credential = ?, status = ?, sort_order = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                plan.getContent(), plan.getDescription(),
                Timestamp.from(plan.getScheduledAt().toInstant()), plan.getTimezone(),
                plan.getReminderEnabled(), plan.getReminderMinutesBefore(),
                plan.getPushPlatform(), plan.getPushTargetId(), plan.getPushCredential(),
                plan.getStatus(), plan.getSortOrder(), plan.getUpdatedBy(), plan.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未来计划不存在");
        }
        return findById(plan.getId()).orElseThrow();
    }

    public Optional<FuturePlan> findById(Long id) {
        List<FuturePlan> results = jdbcTemplate.query(
                "select id, user_id, content, description, scheduled_at, timezone, reminder_enabled, reminder_minutes_before, push_platform, push_target_id, push_credential, status, sort_order, created_by, created_at, updated_by, updated_at, deleted " +
                        "from future_plans where id = ? and deleted = 0",
                planMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<FuturePlan> findByUserId(Long userId) {
        return jdbcTemplate.query(
                "select id, user_id, content, description, scheduled_at, timezone, reminder_enabled, reminder_minutes_before, push_platform, push_target_id, push_credential, status, sort_order, created_by, created_at, updated_by, updated_at, deleted " +
                        "from future_plans where user_id = ? and deleted = 0 order by scheduled_at asc, sort_order asc",
                planMapper(), userId
        );
    }

    public List<FuturePlan> findByUserIdAndStatus(Long userId, String status) {
        return jdbcTemplate.query(
                "select id, user_id, content, description, scheduled_at, timezone, reminder_enabled, reminder_minutes_before, push_platform, push_target_id, push_credential, status, sort_order, created_by, created_at, updated_by, updated_at, deleted " +
                        "from future_plans where user_id = ? and status = ? and deleted = 0 order by scheduled_at asc",
                planMapper(), userId, status
        );
    }

    public List<FuturePlan> findPendingReminders(OffsetDateTime before) {
        return jdbcTemplate.query(
                "select id, user_id, content, description, scheduled_at, timezone, reminder_enabled, reminder_minutes_before, push_platform, push_target_id, push_credential, status, sort_order, created_by, created_at, updated_by, updated_at, deleted " +
                        "from future_plans where status = 'PENDING' and reminder_enabled = true and scheduled_at - make_interval(mins => reminder_minutes_before) <= ? " +
                        "and deleted = 0 order by scheduled_at asc",
                planMapper(), Timestamp.from(before.toInstant())
        );
    }

    public void updateStatus(Long id, String status, Long updatedBy) {
        jdbcTemplate.update(
                "update future_plans set status = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                status, updatedBy, id
        );
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update future_plans set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    private RowMapper<FuturePlan> planMapper() {
        return (rs, rowNum) -> mapPlan(rs);
    }

    private FuturePlan mapPlan(ResultSet rs) throws SQLException {
        FuturePlan plan = new FuturePlan();
        plan.setId(rs.getLong("id"));
        plan.setUserId(rs.getLong("user_id"));
        plan.setContent(rs.getString("content"));
        plan.setDescription(rs.getString("description"));
        Timestamp scheduledAt = rs.getTimestamp("scheduled_at");
        plan.setScheduledAt(scheduledAt == null ? null : scheduledAt.toInstant().atOffset(java.time.ZoneOffset.UTC));
        plan.setTimezone(rs.getString("timezone"));
        plan.setReminderEnabled(rs.getBoolean("reminder_enabled"));
        plan.setReminderMinutesBefore(rs.getInt("reminder_minutes_before"));
        plan.setPushPlatform(rs.getString("push_platform"));
        plan.setPushTargetId(rs.getString("push_target_id"));
        plan.setPushCredential(rs.getString("push_credential"));
        plan.setStatus(rs.getString("status"));
        plan.setSortOrder(rs.getInt("sort_order"));
        plan.setCreatedBy(rs.getObject("created_by", Long.class));
        plan.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        plan.setUpdatedBy(rs.getObject("updated_by", Long.class));
        plan.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        plan.setDeleted(rs.getInt("deleted"));
        return plan;
    }
}
