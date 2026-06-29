package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.PushTarget;
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
public class PushTargetRepository {

    private final JdbcTemplate jdbcTemplate;

    public PushTarget insert(PushTarget target) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into push_targets (user_id, name, platform, target_type, target_id, credential_id, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[]{"id"}
            );
            ps.setLong(1, target.getUserId());
            ps.setString(2, target.getName());
            ps.setString(3, target.getPlatform());
            ps.setString(4, target.getTargetType());
            ps.setString(5, target.getTargetId());
            ps.setLong(6, target.getCredentialId());
            ps.setObject(7, target.getCreatedBy());
            ps.setObject(8, target.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "推送目标保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "推送目标保存后无法查询"));
    }

    public PushTarget update(PushTarget target) {
        int rows = jdbcTemplate.update(
            "update push_targets set name = ?, platform = ?, target_type = ?, target_id = ?, credential_id = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                "where id = ? and deleted = 0",
            target.getName(), target.getPlatform(), target.getTargetType(), target.getTargetId(),
            target.getCredentialId(), target.getUpdatedBy(), target.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "推送目标不存在");
        }
        return findById(target.getId()).orElseThrow();
    }

    public Optional<PushTarget> findById(Long id) {
        List<PushTarget> results = jdbcTemplate.query(
            "select id, user_id, name, platform, target_type, target_id, credential_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from push_targets where id = ? and deleted = 0",
            targetMapper(), id
        );
        return results.stream().findFirst();
    }

    public Optional<PushTarget> findByIdAndUserId(Long id, Long userId) {
        List<PushTarget> results = jdbcTemplate.query(
            "select id, user_id, name, platform, target_type, target_id, credential_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from push_targets where id = ? and user_id = ? and deleted = 0",
            targetMapper(), id, userId
        );
        return results.stream().findFirst();
    }

    public List<PushTarget> findByUserId(Long userId) {
        return jdbcTemplate.query(
            "select id, user_id, name, platform, target_type, target_id, credential_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from push_targets where user_id = ? and deleted = 0 order by id desc",
            targetMapper(), userId
        );
    }

    public List<PushTarget> findByCredentialId(Long credentialId) {
        return jdbcTemplate.query(
            "select id, user_id, name, platform, target_type, target_id, credential_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from push_targets where credential_id = ? and deleted = 0 order by id desc",
            targetMapper(), credentialId
        );
    }

    public List<PushTarget> findByPlatformAndTargetId(String platform, String targetId) {
        return jdbcTemplate.query(
            "select id, user_id, name, platform, target_type, target_id, credential_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from push_targets where platform = ? and target_id = ? and deleted = 0 order by id desc limit 1",
            targetMapper(), platform, targetId
        );
    }

    public List<PushTarget> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query(
            "select id, user_id, name, platform, target_type, target_id, credential_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from push_targets where id in (" + placeholders + ") and deleted = 0 order by id asc",
            targetMapper(), ids.toArray()
        );
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
            "update push_targets set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
            updatedBy, id
        );
    }

    private RowMapper<PushTarget> targetMapper() {
        return (rs, rowNum) -> mapTarget(rs);
    }

    private PushTarget mapTarget(ResultSet rs) throws SQLException {
        PushTarget target = new PushTarget();
        target.setId(rs.getLong("id"));
        target.setUserId(rs.getLong("user_id"));
        target.setName(rs.getString("name"));
        target.setPlatform(rs.getString("platform"));
        target.setTargetType(rs.getString("target_type"));
        target.setTargetId(rs.getString("target_id"));
        target.setCredentialId(rs.getLong("credential_id"));
        target.setCreatedBy(rs.getObject("created_by", Long.class));
        target.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        target.setUpdatedBy(rs.getObject("updated_by", Long.class));
        target.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        target.setDeleted(rs.getInt("deleted"));
        return target;
    }
}
