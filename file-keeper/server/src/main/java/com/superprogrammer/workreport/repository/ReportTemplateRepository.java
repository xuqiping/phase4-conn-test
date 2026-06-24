package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.ReportTemplate;
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
public class ReportTemplateRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReportTemplate insert(ReportTemplate template) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into report_templates (user_id, name, type, content, is_default, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[] { "id" }
            );
            ps.setObject(1, template.getUserId());
            ps.setString(2, template.getName());
            ps.setString(3, template.getType());
            ps.setString(4, template.getContent());
            ps.setBoolean(5, template.getIsDefault() != null && template.getIsDefault());
            ps.setObject(6, template.getCreatedBy());
            ps.setObject(7, template.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "报告模板保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "报告模板保存后无法查询"));
    }

    public ReportTemplate update(ReportTemplate template) {
        int rows = jdbcTemplate.update(
                "update report_templates set name = ?, type = ?, content = ?, is_default = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                template.getName(), template.getType(), template.getContent(), template.getIsDefault(),
                template.getUpdatedBy(), template.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "报告模板不存在");
        }
        return findById(template.getId()).orElseThrow();
    }

    public Optional<ReportTemplate> findById(Long id) {
        List<ReportTemplate> results = jdbcTemplate.query(
                "select id, user_id, name, type, content, is_default, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_templates where id = ? and deleted = 0",
                templateMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<ReportTemplate> findByType(String type) {
        return jdbcTemplate.query(
                "select id, user_id, name, type, content, is_default, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_templates where type = ? and deleted = 0 order by is_default desc, id asc",
                templateMapper(), type
        );
    }

    public Optional<ReportTemplate> findDefaultByType(String type) {
        List<ReportTemplate> results = jdbcTemplate.query(
                "select id, user_id, name, type, content, is_default, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_templates where type = ? and is_default = true and deleted = 0 limit 1",
                templateMapper(), type
        );
        return results.stream().findFirst();
    }

    public List<ReportTemplate> findByUserIdOrDefault(Long userId) {
        return jdbcTemplate.query(
                "select id, user_id, name, type, content, is_default, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_templates where (user_id = ? or user_id is null) and deleted = 0 order by type, is_default desc, id asc",
                templateMapper(), userId
        );
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update report_templates set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    private RowMapper<ReportTemplate> templateMapper() {
        return (rs, rowNum) -> mapTemplate(rs);
    }

    private ReportTemplate mapTemplate(ResultSet rs) throws SQLException {
        ReportTemplate template = new ReportTemplate();
        template.setId(rs.getLong("id"));
        template.setUserId(rs.getObject("user_id", Long.class));
        template.setName(rs.getString("name"));
        template.setType(rs.getString("type"));
        template.setContent(rs.getString("content"));
        template.setIsDefault(rs.getBoolean("is_default"));
        template.setCreatedBy(rs.getObject("created_by", Long.class));
        template.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        template.setUpdatedBy(rs.getObject("updated_by", Long.class));
        template.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        template.setDeleted(rs.getInt("deleted"));
        return template;
    }
}
