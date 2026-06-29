package com.superprogrammer.ai.repository;

import com.superprogrammer.ai.entity.AiConfig;
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
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
public class AiConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public AiConfig insert(AiConfig config) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into ai_configs (user_id, name, provider, model, api_key_enc, endpoint, max_tokens, timeout_seconds, is_default, enabled, created_by, created_at, updated_by, updated_at, deleted) " +
                            "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                    new String[]{"id"}
            );
            ps.setLong(1, config.getUserId());
            ps.setString(2, config.getName());
            ps.setString(3, config.getProvider());
            ps.setString(4, config.getModel());
            ps.setString(5, config.getApiKeyEnc());
            ps.setString(6, config.getEndpoint());
            ps.setInt(7, config.getMaxTokens());
            ps.setInt(8, config.getTimeoutSeconds());
            ps.setBoolean(9, config.getIsDefault() != null && config.getIsDefault());
            ps.setBoolean(10, config.getEnabled() != null && config.getEnabled());
            ps.setObject(11, config.getCreatedBy());
            ps.setObject(12, config.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 配置保存后无法获取主键");
        }
        return findById(generatedId.longValue())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "AI 配置保存后无法查询"));
    }

    public AiConfig update(AiConfig config) {
        int rows = jdbcTemplate.update(
                "update ai_configs set name = ?, provider = ?, model = ?, api_key_enc = ?, endpoint = ?, max_tokens = ?, timeout_seconds = ?, is_default = ?, enabled = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                config.getName(), config.getProvider(), config.getModel(), config.getApiKeyEnc(),
                config.getEndpoint(), config.getMaxTokens(), config.getTimeoutSeconds(),
                config.getIsDefault(), config.getEnabled(), config.getUpdatedBy(), config.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "AI 配置不存在");
        }
        return findById(config.getId()).orElseThrow();
    }

    public Optional<AiConfig> findById(Long id) {
        List<AiConfig> results = jdbcTemplate.query(
                "select id, user_id, name, provider, model, api_key_enc, endpoint, max_tokens, timeout_seconds, is_default, enabled, created_by, created_at, updated_by, updated_at, deleted " +
                        "from ai_configs where id = ? and deleted = 0",
                configMapper(), id
        );
        return results.stream().findFirst();
    }

    public Optional<AiConfig> findByIdAndUserId(Long id, Long userId) {
        List<AiConfig> results = jdbcTemplate.query(
                "select id, user_id, name, provider, model, api_key_enc, endpoint, max_tokens, timeout_seconds, is_default, enabled, created_by, created_at, updated_by, updated_at, deleted " +
                        "from ai_configs where id = ? and user_id = ? and deleted = 0",
                configMapper(), id, userId
        );
        return results.stream().findFirst();
    }

    public List<AiConfig> findByUserId(Long userId) {
        return jdbcTemplate.query(
                "select id, user_id, name, provider, model, api_key_enc, endpoint, max_tokens, timeout_seconds, is_default, enabled, created_by, created_at, updated_by, updated_at, deleted " +
                        "from ai_configs where user_id = ? and deleted = 0 order by is_default desc, id desc",
                configMapper(), userId
        );
    }

    public Optional<AiConfig> findDefaultByUserId(Long userId) {
        List<AiConfig> results = jdbcTemplate.query(
                "select id, user_id, name, provider, model, api_key_enc, endpoint, max_tokens, timeout_seconds, is_default, enabled, created_by, created_at, updated_by, updated_at, deleted " +
                        "from ai_configs where user_id = ? and is_default = true and enabled = true and deleted = 0 " +
                        "order by id desc limit 1",
                configMapper(), userId
        );
        return results.stream().findFirst();
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update ai_configs set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    public void clearDefaultByUserId(Long userId, Long updatedBy) {
        jdbcTemplate.update(
                "update ai_configs set is_default = false, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where user_id = ? and is_default = true and deleted = 0",
                updatedBy, userId
        );
    }

    private RowMapper<AiConfig> configMapper() {
        return (rs, rowNum) -> mapConfig(rs);
    }

    private AiConfig mapConfig(ResultSet rs) throws SQLException {
        AiConfig config = new AiConfig();
        config.setId(rs.getLong("id"));
        config.setUserId(rs.getLong("user_id"));
        config.setName(rs.getString("name"));
        config.setProvider(rs.getString("provider"));
        config.setModel(rs.getString("model"));
        config.setApiKeyEnc(rs.getString("api_key_enc"));
        config.setEndpoint(rs.getString("endpoint"));
        config.setMaxTokens(rs.getInt("max_tokens"));
        config.setTimeoutSeconds(rs.getInt("timeout_seconds"));
        config.setIsDefault(rs.getBoolean("is_default"));
        config.setEnabled(rs.getBoolean("enabled"));
        config.setCreatedBy(rs.getObject("created_by", Long.class));
        config.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        config.setUpdatedBy(rs.getObject("updated_by", Long.class));
        config.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        config.setDeleted(rs.getInt("deleted"));
        return config;
    }
}
