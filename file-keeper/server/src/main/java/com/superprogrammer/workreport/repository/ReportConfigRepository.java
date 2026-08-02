package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.ReportConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ReportConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReportConfig insert(ReportConfig config) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into report_configs (user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, ai_config_id, include_inspiration_digest, inspiration_review_enabled, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[] { "id" }
            );
            ps.setLong(1, config.getUserId());
            ps.setString(2, config.getName());
            ps.setString(3, config.getReportType());
            ps.setLong(4, config.getTemplateId());
            ps.setString(5, config.getCronExpression());
            ps.setString(6, config.getTimezone());
            ps.setBoolean(7, config.getEnabled() != null && config.getEnabled());
            ps.setBoolean(8, config.getAiEnabled() != null && config.getAiEnabled());
            ps.setObject(9, config.getAiConfigId());
            ps.setBoolean(10, config.getIncludeInspirationDigest() != null && config.getIncludeInspirationDigest());
            ps.setBoolean(11, config.getInspirationReviewEnabled() != null && config.getInspirationReviewEnabled());
            ps.setObject(12, config.getCreatedBy());
            ps.setObject(13, config.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "报告配置保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "报告配置保存后无法查询"));
    }

    public ReportConfig update(ReportConfig config) {
        int rows = jdbcTemplate.update(
                "update report_configs set name = ?, report_type = ?, template_id = ?, cron_expression = ?, timezone = ?, enabled = ?, ai_enabled = ?, ai_config_id = ?, include_inspiration_digest = ?, inspiration_review_enabled = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                config.getName(), config.getReportType(), config.getTemplateId(), config.getCronExpression(),
                config.getTimezone(), config.getEnabled(), config.getAiEnabled(), config.getAiConfigId(),
                config.getIncludeInspirationDigest(), config.getInspirationReviewEnabled(),
                config.getUpdatedBy(), config.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "报告配置不存在");
        }
        return findById(config.getId()).orElseThrow();
    }

    public Optional<ReportConfig> findById(Long id) {
        List<ReportConfig> results = jdbcTemplate.query(
                "select id, user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, ai_config_id, include_inspiration_digest, inspiration_review_enabled, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_configs where id = ? and deleted = 0",
                configMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<ReportConfig> findByUserId(Long userId) {
        return jdbcTemplate.query(
                "select id, user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, ai_config_id, include_inspiration_digest, inspiration_review_enabled, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_configs where user_id = ? and deleted = 0 order by id desc",
                configMapper(), userId
        );
    }

    public List<ReportConfig> findEnabledByUserId(Long userId) {
        return jdbcTemplate.query(
                "select id, user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, ai_config_id, include_inspiration_digest, inspiration_review_enabled, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_configs where user_id = ? and enabled = true and deleted = 0 order by id desc",
                configMapper(), userId
        );
    }

    public List<ReportConfig> findEnabled() {
        return jdbcTemplate.query(
                "select id, user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, ai_config_id, include_inspiration_digest, inspiration_review_enabled, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_configs where enabled = true and deleted = 0 order by id desc",
                configMapper()
        );
    }

    public List<ReportConfig> findByInspirationReviewEnabled(boolean enabled) {
        return jdbcTemplate.query(
                "select id, user_id, name, report_type, template_id, cron_expression, timezone, enabled, ai_enabled, ai_config_id, include_inspiration_digest, inspiration_review_enabled, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_configs where inspiration_review_enabled = ? and deleted = 0 order by id desc",
                configMapper(), enabled
        );
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update report_configs set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    private RowMapper<ReportConfig> configMapper() {
        return (rs, rowNum) -> mapConfig(rs);
    }

    private ReportConfig mapConfig(ResultSet rs) throws SQLException {
        ReportConfig config = new ReportConfig();
        config.setId(rs.getLong("id"));
        config.setUserId(rs.getLong("user_id"));
        config.setName(rs.getString("name"));
        config.setReportType(rs.getString("report_type"));
        config.setTemplateId(rs.getLong("template_id"));
        config.setCronExpression(rs.getString("cron_expression"));
        config.setTimezone(rs.getString("timezone"));
        config.setEnabled(rs.getBoolean("enabled"));
        config.setAiEnabled(rs.getBoolean("ai_enabled"));
        config.setAiConfigId(rs.getObject("ai_config_id", Long.class));
        config.setIncludeInspirationDigest(rs.getBoolean("include_inspiration_digest"));
        config.setInspirationReviewEnabled(rs.getBoolean("inspiration_review_enabled"));
        config.setCreatedBy(rs.getObject("created_by", Long.class));
        config.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        config.setUpdatedBy(rs.getObject("updated_by", Long.class));
        config.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        config.setDeleted(rs.getInt("deleted"));
        return config;
    }
}
