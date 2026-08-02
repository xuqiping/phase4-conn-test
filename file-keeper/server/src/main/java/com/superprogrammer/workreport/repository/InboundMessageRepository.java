package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.InboundMessage;
import com.superprogrammer.workreport.entity.InboundMessageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class InboundMessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public InboundMessage insert(InboundMessage message) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                    "insert into inbound_messages (user_id, platform, platform_message_id, sender_id, sender_name, raw_text, intent, confidence, parsed_payload, status, target_module, target_id, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                    new String[] { "id" }
                );
                ps.setLong(1, message.getUserId());
                ps.setString(2, message.getPlatform());
                ps.setString(3, message.getPlatformMessageId());
                ps.setString(4, message.getSenderId());
                ps.setString(5, message.getSenderName());
                ps.setString(6, message.getRawText());
                ps.setString(7, message.getIntent());
                ps.setBigDecimal(8, message.getConfidence());
                ps.setString(9, message.getParsedPayload());
                ps.setString(10, message.getStatus());
                ps.setString(11, message.getTargetModule());
                ps.setObject(12, message.getTargetId());
                ps.setObject(13, message.getCreatedBy());
                ps.setObject(14, message.getUpdatedBy());
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            Optional<InboundMessage> existing = findByPlatformAndMessageId(message.getPlatform(), message.getPlatformMessageId());
            return existing.orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "消息已存在但无法查询"));
        }
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "入站消息保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "入站消息保存后无法查询"));
    }

    public InboundMessage update(InboundMessage message) {
        jdbcTemplate.update(
            "update inbound_messages set status = ?, target_module = ?, target_id = ?, parsed_payload = ?::jsonb, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                "where id = ? and deleted = 0",
            message.getStatus(), message.getTargetModule(), message.getTargetId(),
            message.getParsedPayload(), message.getUpdatedBy(), message.getId()
        );
        return findById(message.getId()).orElseThrow();
    }

    public Optional<InboundMessage> findById(Long id) {
        List<InboundMessage> results = jdbcTemplate.query(
            "select id, user_id, platform, platform_message_id, sender_id, sender_name, raw_text, intent, confidence, parsed_payload, status, target_module, target_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from inbound_messages where id = ? and deleted = 0",
            messageMapper(), id
        );
        return results.stream().findFirst();
    }

    public Optional<InboundMessage> findByPlatformAndMessageId(String platform, String platformMessageId) {
        List<InboundMessage> results = jdbcTemplate.query(
            "select id, user_id, platform, platform_message_id, sender_id, sender_name, raw_text, intent, confidence, parsed_payload, status, target_module, target_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from inbound_messages where platform = ? and platform_message_id = ? and deleted = 0",
            messageMapper(), platform, platformMessageId
        );
        return results.stream().findFirst();
    }

    public List<InboundMessage> findByUserIdAndStatus(Long userId, String status, int limit) {
        return jdbcTemplate.query(
            "select id, user_id, platform, platform_message_id, sender_id, sender_name, raw_text, intent, confidence, parsed_payload, status, target_module, target_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from inbound_messages where user_id = ? and status = ? and deleted = 0 order by created_at desc limit ?",
            messageMapper(), userId, status, limit
        );
    }

    public List<InboundMessage> findPendingByUserId(Long userId, int limit) {
        return findByUserIdAndStatus(userId, "PENDING", limit);
    }

    public List<InboundMessage> findConfirmedWorkLogsByUserIdAndDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return jdbcTemplate.query(
            "select id, user_id, platform, platform_message_id, sender_id, sender_name, raw_text, intent, confidence, parsed_payload, status, target_module, target_id, created_by, created_at, updated_by, updated_at, deleted " +
                "from inbound_messages where user_id = ? and status = ? and target_module = ? and deleted = 0 and created_at::date between ? and ? order by created_at desc",
            messageMapper(), userId, InboundMessageStatus.CONFIRMED.name(), "work_log", java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate)
        );
    }

    private RowMapper<InboundMessage> messageMapper() {
        return (rs, rowNum) -> mapMessage(rs);
    }

    private InboundMessage mapMessage(ResultSet rs) throws SQLException {
        InboundMessage message = new InboundMessage();
        message.setId(rs.getLong("id"));
        message.setUserId(rs.getLong("user_id"));
        message.setPlatform(rs.getString("platform"));
        message.setPlatformMessageId(rs.getString("platform_message_id"));
        message.setSenderId(rs.getString("sender_id"));
        message.setSenderName(rs.getString("sender_name"));
        message.setRawText(rs.getString("raw_text"));
        message.setIntent(rs.getString("intent"));
        message.setConfidence(rs.getBigDecimal("confidence"));
        message.setParsedPayload(rs.getString("parsed_payload"));
        message.setStatus(rs.getString("status"));
        message.setTargetModule(rs.getString("target_module"));
        message.setTargetId(rs.getObject("target_id", Long.class));
        message.setCreatedBy(rs.getObject("created_by", Long.class));
        message.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        message.setUpdatedBy(rs.getObject("updated_by", Long.class));
        message.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        message.setDeleted(rs.getInt("deleted"));
        return message;
    }
}
