package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.WorkLog;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class WorkLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkLog insert(WorkLog log) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into work_logs (user_id, log_date, content, tags, source, sort_order, platform_message_id, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[] { "id" }
            );
            ps.setLong(1, log.getUserId());
            ps.setDate(2, Date.valueOf(log.getLogDate()));
            ps.setString(3, log.getContent());
            ps.setString(4, log.getTags());
            ps.setString(5, log.getSource());
            ps.setInt(6, log.getSortOrder() == null ? 0 : log.getSortOrder());
            ps.setString(7, log.getPlatformMessageId());
            ps.setObject(8, log.getCreatedBy());
            ps.setObject(9, log.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "工作记录保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "工作记录保存后无法查询"));
    }

    public WorkLog update(WorkLog log) {
        int rows = jdbcTemplate.update(
                "update work_logs set content = ?, tags = ?, source = ?, sort_order = ?, platform_message_id = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                log.getContent(), log.getTags(), log.getSource(), log.getSortOrder(), log.getPlatformMessageId(),
                log.getUpdatedBy(), log.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作记录不存在");
        }
        return findById(log.getId()).orElseThrow();
    }

    public Optional<WorkLog> findById(Long id) {
        List<WorkLog> results = jdbcTemplate.query(
                "select id, user_id, log_date, content, tags, source, sort_order, platform_message_id, created_by, created_at, updated_by, updated_at, deleted " +
                        "from work_logs where id = ? and deleted = 0",
                workLogMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<WorkLog> findByUserIdAndDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return jdbcTemplate.query(
                "select id, user_id, log_date, content, tags, source, sort_order, platform_message_id, created_by, created_at, updated_by, updated_at, deleted " +
                        "from work_logs where user_id = ? and log_date between ? and ? and deleted = 0 order by log_date desc, sort_order asc, id desc",
                workLogMapper(), userId, Date.valueOf(startDate), Date.valueOf(endDate)
        );
    }

    public List<WorkLog> findByUserIdAndDate(Long userId, LocalDate logDate) {
        return jdbcTemplate.query(
                "select id, user_id, log_date, content, tags, source, sort_order, platform_message_id, created_by, created_at, updated_by, updated_at, deleted " +
                        "from work_logs where user_id = ? and log_date = ? and deleted = 0 order by sort_order asc, id desc",
                workLogMapper(), userId, Date.valueOf(logDate)
        );
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update work_logs set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    private RowMapper<WorkLog> workLogMapper() {
        return (rs, rowNum) -> mapWorkLog(rs);
    }

    private WorkLog mapWorkLog(ResultSet rs) throws SQLException {
        WorkLog log = new WorkLog();
        log.setId(rs.getLong("id"));
        log.setUserId(rs.getLong("user_id"));
        log.setLogDate(rs.getDate("log_date").toLocalDate());
        log.setContent(rs.getString("content"));
        log.setTags(rs.getString("tags"));
        log.setSource(rs.getString("source"));
        log.setSortOrder(rs.getInt("sort_order"));
        log.setPlatformMessageId(rs.getString("platform_message_id"));
        log.setCreatedBy(rs.getObject("created_by", Long.class));
        log.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        log.setUpdatedBy(rs.getObject("updated_by", Long.class));
        log.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        log.setDeleted(rs.getInt("deleted"));
        return log;
    }
}
