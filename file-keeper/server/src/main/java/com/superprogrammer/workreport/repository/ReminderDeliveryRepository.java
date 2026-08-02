package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.ReminderDelivery;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ReminderDeliveryRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReminderDelivery insert(ReminderDelivery delivery) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into reminder_deliveries (source_type, source_id, user_id, platform, target_id, credential, push_target_id, status, response, tried_count, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[] { "id" }
            );
            ps.setString(1, delivery.getSourceType());
            ps.setLong(2, delivery.getSourceId());
            ps.setLong(3, delivery.getUserId());
            ps.setString(4, delivery.getPlatform());
            ps.setString(5, delivery.getTargetId());
            ps.setString(6, delivery.getCredential());
            ps.setObject(7, delivery.getPushTargetId());
            ps.setString(8, delivery.getStatus());
            ps.setString(9, delivery.getResponse());
            ps.setInt(10, delivery.getTriedCount() == null ? 0 : delivery.getTriedCount());
            ps.setObject(11, delivery.getCreatedBy());
            ps.setObject(12, delivery.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "提醒推送记录保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "提醒推送记录保存后无法查询"));
    }

    public ReminderDelivery update(ReminderDelivery delivery) {
        jdbcTemplate.update(
                "update reminder_deliveries set status = ?, response = ?, tried_count = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                delivery.getStatus(), delivery.getResponse(), delivery.getTriedCount(),
                delivery.getUpdatedBy(), delivery.getId()
        );
        return findById(delivery.getId()).orElseThrow();
    }

    public Optional<ReminderDelivery> findById(Long id) {
        List<ReminderDelivery> results = jdbcTemplate.query(
                "select id, source_type, source_id, user_id, platform, target_id, credential, push_target_id, status, response, tried_count, created_by, created_at, updated_by, updated_at, deleted " +
                        "from reminder_deliveries where id = ? and deleted = 0",
                deliveryMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<ReminderDelivery> findFailedWithin(LocalDateTime since) {
        return jdbcTemplate.query(
                "select id, source_type, source_id, user_id, platform, target_id, credential, push_target_id, status, response, tried_count, created_by, created_at, updated_by, updated_at, deleted " +
                        "from reminder_deliveries where status != 'SUCCESS' and created_at >= ? and tried_count < 3 and deleted = 0",
                deliveryMapper(), since
        );
    }

    private RowMapper<ReminderDelivery> deliveryMapper() {
        return (rs, rowNum) -> mapDelivery(rs);
    }

    private ReminderDelivery mapDelivery(ResultSet rs) throws SQLException {
        ReminderDelivery delivery = new ReminderDelivery();
        delivery.setId(rs.getLong("id"));
        delivery.setSourceType(rs.getString("source_type"));
        delivery.setSourceId(rs.getLong("source_id"));
        delivery.setUserId(rs.getLong("user_id"));
        delivery.setPlatform(rs.getString("platform"));
        delivery.setTargetId(rs.getString("target_id"));
        delivery.setCredential(rs.getString("credential"));
        delivery.setPushTargetId(rs.getObject("push_target_id", Long.class));
        delivery.setStatus(rs.getString("status"));
        delivery.setResponse(rs.getString("response"));
        delivery.setTriedCount(rs.getInt("tried_count"));
        delivery.setCreatedBy(rs.getObject("created_by", Long.class));
        delivery.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        delivery.setUpdatedBy(rs.getObject("updated_by", Long.class));
        delivery.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        delivery.setDeleted(rs.getInt("deleted"));
        return delivery;
    }
}
