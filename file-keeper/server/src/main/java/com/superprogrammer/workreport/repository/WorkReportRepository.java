package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.WorkReport;
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
public class WorkReportRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkReport insert(WorkReport report) {
        Timestamp generatedAt = report.getGeneratedAt() != null ? Timestamp.from(report.getGeneratedAt().toInstant()) : null;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into work_reports (user_id, config_id, report_type, title, content, generated_at, status, completion_rate, consecutive_miss_days, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[] { "id" }
            );
            ps.setLong(1, report.getUserId());
            ps.setLong(2, report.getConfigId());
            ps.setString(3, report.getReportType());
            ps.setString(4, report.getTitle());
            ps.setString(5, report.getContent());
            ps.setTimestamp(6, generatedAt);
            ps.setString(7, report.getStatus());
            ps.setObject(8, report.getCompletionRate());
            ps.setObject(9, report.getConsecutiveMissDays());
            ps.setObject(10, report.getCreatedBy());
            ps.setObject(11, report.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "报告保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "报告保存后无法查询"));
    }

    public WorkReport update(WorkReport report) {
        int rows = jdbcTemplate.update(
                "update work_reports set title = ?, content = ?, status = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                report.getTitle(), report.getContent(), report.getStatus(),
                report.getUpdatedBy(), report.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "报告不存在");
        }
        return findById(report.getId()).orElseThrow();
    }

    public Optional<WorkReport> findById(Long id) {
        List<WorkReport> results = jdbcTemplate.query(
                "select id, user_id, config_id, report_type, title, content, generated_at, status, completion_rate, consecutive_miss_days, created_by, created_at, updated_by, updated_at, deleted " +
                        "from work_reports where id = ? and deleted = 0",
                reportMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<WorkReport> findByUserId(Long userId, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (safePage - 1) * safeSize;
        return jdbcTemplate.query(
                "select id, user_id, config_id, report_type, title, content, generated_at, status, completion_rate, consecutive_miss_days, created_by, created_at, updated_by, updated_at, deleted " +
                        "from work_reports where user_id = ? and deleted = 0 order by generated_at desc limit ? offset ?",
                reportMapper(), userId, safeSize, offset
        );
    }

    public Long countByUserId(Long userId) {
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from work_reports where user_id = ? and deleted = 0",
                Long.class, userId
        );
        return total == null ? 0 : total;
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update work_reports set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    private RowMapper<WorkReport> reportMapper() {
        return (rs, rowNum) -> mapReport(rs);
    }

    private WorkReport mapReport(ResultSet rs) throws SQLException {
        WorkReport report = new WorkReport();
        report.setId(rs.getLong("id"));
        report.setUserId(rs.getLong("user_id"));
        report.setConfigId(rs.getLong("config_id"));
        report.setReportType(rs.getString("report_type"));
        report.setTitle(rs.getString("title"));
        report.setContent(rs.getString("content"));
        Timestamp generatedAt = rs.getTimestamp("generated_at");
        if (generatedAt != null) {
            report.setGeneratedAt(generatedAt.toInstant().atOffset(java.time.ZoneOffset.UTC));
        }
        report.setStatus(rs.getString("status"));
        report.setCompletionRate(rs.getObject("completion_rate", Double.class));
        report.setConsecutiveMissDays(rs.getObject("consecutive_miss_days", Integer.class));
        report.setCreatedBy(rs.getObject("created_by", Long.class));
        report.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        report.setUpdatedBy(rs.getObject("updated_by", Long.class));
        report.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        report.setDeleted(rs.getInt("deleted"));
        return report;
    }
}
