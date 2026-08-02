package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.PushDelivery;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PushDeliveryRepository {

    private final JdbcTemplate jdbcTemplate;

    public PushDelivery insert(PushDelivery delivery) {
        jdbcTemplate.update(
                "insert into push_deliveries (report_id, target_id, status, response, tried_count, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                delivery.getReportId(), delivery.getTargetId(), delivery.getStatus(),
                delivery.getResponse(), delivery.getTriedCount(), delivery.getCreatedBy(), delivery.getUpdatedBy()
        );
        return findLatestByReportId(delivery.getReportId()).orElseThrow();
    }

    public PushDelivery update(PushDelivery delivery) {
        int rows = jdbcTemplate.update(
                "update push_deliveries set status = ?, response = ?, tried_count = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                delivery.getStatus(), delivery.getResponse(), delivery.getTriedCount(),
                delivery.getUpdatedBy(), delivery.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "推送记录不存在");
        }
        return findById(delivery.getId()).orElseThrow();
    }

    public Optional<PushDelivery> findById(Long id) {
        List<PushDelivery> results = jdbcTemplate.query(
                "select id, report_id, target_id, status, response, tried_count, created_by, created_at, updated_by, updated_at, deleted " +
                        "from push_deliveries where id = ? and deleted = 0",
                deliveryMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<PushDelivery> findByReportId(Long reportId) {
        return jdbcTemplate.query(
                "select id, report_id, target_id, status, response, tried_count, created_by, created_at, updated_by, updated_at, deleted " +
                        "from push_deliveries where report_id = ? and deleted = 0 order by id asc",
                deliveryMapper(), reportId
        );
    }

    public List<PushDelivery> findFailedWithin(LocalDateTime since) {
        return jdbcTemplate.query(
                "select id, report_id, target_id, status, response, tried_count, created_by, created_at, updated_by, updated_at, deleted " +
                        "from push_deliveries where status != 'SUCCESS' and created_at >= ? and deleted = 0 order by id asc",
                deliveryMapper(), Timestamp.from(since.atZone(java.time.ZoneId.systemDefault()).toInstant())
        );
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update push_deliveries set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    private Optional<PushDelivery> findLatestByReportId(Long reportId) {
        List<PushDelivery> results = jdbcTemplate.query(
                "select id, report_id, target_id, status, response, tried_count, created_by, created_at, updated_by, updated_at, deleted " +
                        "from push_deliveries where report_id = ? and deleted = 0 order by id desc limit 1",
                deliveryMapper(), reportId
        );
        return results.stream().findFirst();
    }

    private RowMapper<PushDelivery> deliveryMapper() {
        return (rs, rowNum) -> mapDelivery(rs);
    }

    private PushDelivery mapDelivery(ResultSet rs) throws SQLException {
        PushDelivery delivery = new PushDelivery();
        delivery.setId(rs.getLong("id"));
        delivery.setReportId(rs.getLong("report_id"));
        delivery.setTargetId(rs.getLong("target_id"));
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
