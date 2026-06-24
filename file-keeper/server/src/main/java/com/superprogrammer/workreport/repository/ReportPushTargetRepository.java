package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.ReportPushTarget;
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
public class ReportPushTargetRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReportPushTarget insert(ReportPushTarget target) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into report_push_targets (config_id, platform, target_type, target_id, credential, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[] { "id" }
            );
            ps.setLong(1, target.getConfigId());
            ps.setString(2, target.getPlatform());
            ps.setString(3, target.getTargetType());
            ps.setString(4, target.getTargetId());
            ps.setString(5, target.getCredential());
            ps.setObject(6, target.getCreatedBy());
            ps.setObject(7, target.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "推送目标保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "推送目标保存后无法查询"));
    }

    public ReportPushTarget update(ReportPushTarget target) {
        int rows = jdbcTemplate.update(
                "update report_push_targets set platform = ?, target_type = ?, target_id = ?, credential = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                target.getPlatform(), target.getTargetType(), target.getTargetId(), target.getCredential(),
                target.getUpdatedBy(), target.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "推送目标不存在");
        }
        return findById(target.getId()).orElseThrow();
    }

    public Optional<ReportPushTarget> findById(Long id) {
        List<ReportPushTarget> results = jdbcTemplate.query(
                "select id, config_id, platform, target_type, target_id, credential, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_push_targets where id = ? and deleted = 0",
                targetMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<ReportPushTarget> findByConfigId(Long configId) {
        return jdbcTemplate.query(
                "select id, config_id, platform, target_type, target_id, credential, created_by, created_at, updated_by, updated_at, deleted " +
                        "from report_push_targets where config_id = ? and deleted = 0 order by id asc",
                targetMapper(), configId
        );
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update report_push_targets set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    public void softDeleteByConfigId(Long configId, Long updatedBy) {
        jdbcTemplate.update(
                "update report_push_targets set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where config_id = ? and deleted = 0",
                updatedBy, configId
        );
    }

    private RowMapper<ReportPushTarget> targetMapper() {
        return (rs, rowNum) -> mapTarget(rs);
    }

    private ReportPushTarget mapTarget(ResultSet rs) throws SQLException {
        ReportPushTarget target = new ReportPushTarget();
        target.setId(rs.getLong("id"));
        target.setConfigId(rs.getLong("config_id"));
        target.setPlatform(rs.getString("platform"));
        target.setTargetType(rs.getString("target_type"));
        target.setTargetId(rs.getString("target_id"));
        target.setCredential(rs.getString("credential"));
        target.setCreatedBy(rs.getObject("created_by", Long.class));
        target.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        target.setUpdatedBy(rs.getObject("updated_by", Long.class));
        target.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        target.setDeleted(rs.getInt("deleted"));
        return target;
    }
}
