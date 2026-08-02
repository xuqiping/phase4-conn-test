package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.WorkPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class WorkPlanRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkPlan insert(WorkPlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into work_plans (user_id, plan_date, content, description, priority, planned_start_time, planned_end_time, completed, sort_order, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[] { "id" }
            );
            ps.setLong(1, plan.getUserId());
            ps.setDate(2, Date.valueOf(plan.getPlanDate()));
            ps.setString(3, plan.getContent());
            ps.setString(4, plan.getDescription());
            ps.setString(5, plan.getPriority());
            ps.setTime(6, timeValue(plan.getPlannedStartTime()));
            ps.setTime(7, timeValue(plan.getPlannedEndTime()));
            ps.setBoolean(8, plan.getCompleted() != null && plan.getCompleted());
            ps.setInt(9, plan.getSortOrder() == null ? 0 : plan.getSortOrder());
            ps.setObject(10, plan.getCreatedBy());
            ps.setObject(11, plan.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "每日安排保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "每日安排保存后无法查询"));
    }

    public WorkPlan update(WorkPlan plan) {
        int rows = jdbcTemplate.update(
                "update work_plans set content = ?, description = ?, priority = ?, planned_start_time = ?, planned_end_time = ?, completed = ?, sort_order = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                plan.getContent(), plan.getDescription(), plan.getPriority(),
                timeValue(plan.getPlannedStartTime()), timeValue(plan.getPlannedEndTime()),
                plan.getCompleted(), plan.getSortOrder(), plan.getUpdatedBy(), plan.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "每日安排不存在");
        }
        return findById(plan.getId()).orElseThrow();
    }

    public Optional<WorkPlan> findById(Long id) {
        List<WorkPlan> results = jdbcTemplate.query(
                "select id, user_id, plan_date, content, description, priority, planned_start_time, planned_end_time, completed, sort_order, created_by, created_at, updated_by, updated_at, deleted " +
                        "from work_plans where id = ? and deleted = 0",
                workPlanMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<WorkPlan> findByUserIdAndDate(Long userId, LocalDate planDate) {
        return jdbcTemplate.query(
                "select id, user_id, plan_date, content, description, priority, planned_start_time, planned_end_time, completed, sort_order, created_by, created_at, updated_by, updated_at, deleted " +
                        "from work_plans where user_id = ? and plan_date = ? and deleted = 0 order by sort_order asc, id asc",
                workPlanMapper(), userId, Date.valueOf(planDate)
        );
    }

    public List<WorkPlan> findByUserIdAndDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return jdbcTemplate.query(
                "select id, user_id, plan_date, content, description, priority, planned_start_time, planned_end_time, completed, sort_order, created_by, created_at, updated_by, updated_at, deleted " +
                        "from work_plans where user_id = ? and plan_date between ? and ? and deleted = 0 order by plan_date asc, sort_order asc",
                workPlanMapper(), userId, Date.valueOf(startDate), Date.valueOf(endDate)
        );
    }

    public void markCompleted(Long id, boolean completed, Long updatedBy) {
        jdbcTemplate.update(
                "update work_plans set completed = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                completed, updatedBy, id
        );
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update work_plans set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    private RowMapper<WorkPlan> workPlanMapper() {
        return (rs, rowNum) -> mapWorkPlan(rs);
    }

    private WorkPlan mapWorkPlan(ResultSet rs) throws SQLException {
        WorkPlan plan = new WorkPlan();
        plan.setId(rs.getLong("id"));
        plan.setUserId(rs.getLong("user_id"));
        plan.setPlanDate(rs.getDate("plan_date").toLocalDate());
        plan.setContent(rs.getString("content"));
        plan.setDescription(rs.getString("description"));
        plan.setPriority(rs.getString("priority"));
        Time startTime = rs.getTime("planned_start_time");
        plan.setPlannedStartTime(startTime != null ? startTime.toLocalTime() : null);
        Time endTime = rs.getTime("planned_end_time");
        plan.setPlannedEndTime(endTime != null ? endTime.toLocalTime() : null);
        plan.setCompleted(rs.getBoolean("completed"));
        plan.setSortOrder(rs.getInt("sort_order"));
        plan.setCreatedBy(rs.getObject("created_by", Long.class));
        plan.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        plan.setUpdatedBy(rs.getObject("updated_by", Long.class));
        plan.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        plan.setDeleted(rs.getInt("deleted"));
        return plan;
    }

    private Time timeValue(LocalTime time) {
        return time == null ? null : Time.valueOf(time);
    }
}
