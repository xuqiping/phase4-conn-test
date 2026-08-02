package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.PushCredential;
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
public class PushCredentialRepository {

    private final JdbcTemplate jdbcTemplate;

    public PushCredential insert(PushCredential credential) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into push_credentials (user_id, name, platform, credential_enc, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[]{"id"}
            );
            ps.setLong(1, credential.getUserId());
            ps.setString(2, credential.getName());
            ps.setString(3, credential.getPlatform());
            ps.setString(4, credential.getCredentialEnc());
            ps.setObject(5, credential.getCreatedBy());
            ps.setObject(6, credential.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "推送凭据保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "推送凭据保存后无法查询"));
    }

    public PushCredential update(PushCredential credential) {
        int rows = jdbcTemplate.update(
            "update push_credentials set name = ?, platform = ?, credential_enc = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                "where id = ? and deleted = 0",
            credential.getName(), credential.getPlatform(), credential.getCredentialEnc(),
            credential.getUpdatedBy(), credential.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "推送凭据不存在");
        }
        return findById(credential.getId()).orElseThrow();
    }

    public Optional<PushCredential> findById(Long id) {
        List<PushCredential> results = jdbcTemplate.query(
            "select id, user_id, name, platform, credential_enc, created_by, created_at, updated_by, updated_at, deleted " +
                "from push_credentials where id = ? and deleted = 0",
            credentialMapper(), id
        );
        return results.stream().findFirst();
    }

    public Optional<PushCredential> findByIdAndUserId(Long id, Long userId) {
        List<PushCredential> results = jdbcTemplate.query(
            "select id, user_id, name, platform, credential_enc, created_by, created_at, updated_by, updated_at, deleted " +
                "from push_credentials where id = ? and user_id = ? and deleted = 0",
            credentialMapper(), id, userId
        );
        return results.stream().findFirst();
    }

    public List<PushCredential> findByUserId(Long userId) {
        return jdbcTemplate.query(
            "select id, user_id, name, platform, credential_enc, created_by, created_at, updated_by, updated_at, deleted " +
                "from push_credentials where user_id = ? and deleted = 0 order by id desc",
            credentialMapper(), userId
        );
    }

    public List<PushCredential> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query(
            "select id, user_id, name, platform, credential_enc, created_by, created_at, updated_by, updated_at, deleted " +
                "from push_credentials where id in (" + placeholders + ") and deleted = 0 order by id asc",
            credentialMapper(), ids.toArray()
        );
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
            "update push_credentials set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
            updatedBy, id
        );
    }

    private RowMapper<PushCredential> credentialMapper() {
        return (rs, rowNum) -> mapCredential(rs);
    }

    private PushCredential mapCredential(ResultSet rs) throws SQLException {
        PushCredential credential = new PushCredential();
        credential.setId(rs.getLong("id"));
        credential.setUserId(rs.getLong("user_id"));
        credential.setName(rs.getString("name"));
        credential.setPlatform(rs.getString("platform"));
        credential.setCredentialEnc(rs.getString("credential_enc"));
        credential.setCreatedBy(rs.getObject("created_by", Long.class));
        credential.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        credential.setUpdatedBy(rs.getObject("updated_by", Long.class));
        credential.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        credential.setDeleted(rs.getInt("deleted"));
        return credential;
    }
}
