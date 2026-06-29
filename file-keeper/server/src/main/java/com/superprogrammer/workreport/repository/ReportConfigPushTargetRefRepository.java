package com.superprogrammer.workreport.repository;

import com.superprogrammer.workreport.entity.ReportConfigPushTargetRef;
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

@Repository
@RequiredArgsConstructor
public class ReportConfigPushTargetRefRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReportConfigPushTargetRef insert(ReportConfigPushTargetRef ref) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into report_config_push_targets (config_id, target_id, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[]{"id"}
            );
            ps.setLong(1, ref.getConfigId());
            ps.setLong(2, ref.getTargetId());
            ps.setObject(3, ref.getCreatedBy());
            ps.setObject(4, ref.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            return ref;
        }
        ref.setId(generatedId.longValue());
        return ref;
    }

    public List<ReportConfigPushTargetRef> findByConfigId(Long configId) {
        return jdbcTemplate.query(
            "select id, config_id, target_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from report_config_push_targets where config_id = ? and deleted = 0 order by id asc",
            refMapper(), configId
        );
    }

    public void softDeleteByConfigId(Long configId, Long updatedBy) {
        jdbcTemplate.update(
            "update report_config_push_targets set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                "where config_id = ? and deleted = 0",
            updatedBy, configId
        );
    }

    public void softDeleteByConfigIdAndTargetIdNotIn(Long configId, List<Long> keptTargetIds, Long updatedBy) {
        if (keptTargetIds == null || keptTargetIds.isEmpty()) {
            softDeleteByConfigId(configId, updatedBy);
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(keptTargetIds.size(), "?"));
        List<Object> params = new java.util.ArrayList<>();
        params.add(updatedBy);
        params.add(configId);
        params.addAll(keptTargetIds);
        jdbcTemplate.update(
            "update report_config_push_targets set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                "where config_id = ? and deleted = 0 and target_id not in (" + placeholders + ")",
            params.toArray()
        );
    }

    public void restoreOrInsert(Long configId, Long targetId, Long userId) {
        Integer existing = jdbcTemplate.queryForObject(
            "select count(*) from report_config_push_targets where config_id = ? and target_id = ?",
            Integer.class, configId, targetId
        );
        if (existing != null && existing > 0) {
            jdbcTemplate.update(
                "update report_config_push_targets set deleted = 0, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                    "where config_id = ? and target_id = ?",
                userId, configId, targetId
            );
        } else {
            ReportConfigPushTargetRef ref = new ReportConfigPushTargetRef();
            ref.setConfigId(configId);
            ref.setTargetId(targetId);
            ref.setCreatedBy(userId);
            ref.setUpdatedBy(userId);
            insert(ref);
        }
    }

    private RowMapper<ReportConfigPushTargetRef> refMapper() {
        return (rs, rowNum) -> mapRef(rs);
    }

    private ReportConfigPushTargetRef mapRef(ResultSet rs) throws SQLException {
        ReportConfigPushTargetRef ref = new ReportConfigPushTargetRef();
        ref.setId(rs.getLong("id"));
        ref.setConfigId(rs.getLong("config_id"));
        ref.setTargetId(rs.getLong("target_id"));
        ref.setCreatedBy(rs.getObject("created_by", Long.class));
        ref.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        ref.setUpdatedBy(rs.getObject("updated_by", Long.class));
        ref.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        ref.setDeleted(rs.getInt("deleted"));
        return ref;
    }
}
