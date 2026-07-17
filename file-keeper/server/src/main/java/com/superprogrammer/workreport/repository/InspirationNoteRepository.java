package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.InspirationNote;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class InspirationNoteRepository {

    private final JdbcTemplate jdbcTemplate;

    public InspirationNote insert(InspirationNote note) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into inspiration_notes (user_id, content, tags, source, platform_message_id, report_config_ids, reviewed_at, created_by, created_at, updated_by, updated_at, deleted) " +
                            "values (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                    new String[] { "id" }
            );
            ps.setLong(1, note.getUserId());
            ps.setString(2, note.getContent());
            ps.setArray(3, toSqlArray(connection, "text", note.getTags()));
            ps.setString(4, note.getSource());
            ps.setString(5, note.getPlatformMessageId());
            ps.setArray(6, toSqlArray(connection, "bigint", note.getReportConfigIds()));
            ps.setTimestamp(7, note.getReviewedAt() == null ? null : Timestamp.valueOf(note.getReviewedAt().toLocalDateTime()));
            ps.setObject(8, note.getCreatedBy());
            ps.setObject(9, note.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "灵感随记保存后无法获取主键");
        }
        return findById(generatedId.longValue())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "灵感随记保存后无法查询"));
    }

    public InspirationNote update(InspirationNote note) {
        int rows = jdbcTemplate.update(
                "update inspiration_notes set content = ?, tags = ?, source = ?, platform_message_id = ?, report_config_ids = ?, reviewed_at = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                note.getContent(),
                note.getTags() == null ? null : note.getTags().toArray(new String[0]),
                note.getSource(),
                note.getPlatformMessageId(),
                note.getReportConfigIds() == null ? null : note.getReportConfigIds().toArray(new Long[0]),
                note.getReviewedAt() == null ? null : Timestamp.valueOf(note.getReviewedAt().toLocalDateTime()),
                note.getUpdatedBy(),
                note.getId()
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "灵感随记不存在");
        }
        return findById(note.getId()).orElseThrow();
    }

    public Optional<InspirationNote> findById(Long id) {
        List<InspirationNote> results = jdbcTemplate.query(
                "select id, user_id, content, tags, source, platform_message_id, report_config_ids, reviewed_at, created_by, created_at, updated_by, updated_at, deleted " +
                        "from inspiration_notes where id = ? and deleted = 0",
                noteMapper(), id
        );
        return results.stream().findFirst();
    }

    public List<InspirationNote> findByUserId(Long userId) {
        return jdbcTemplate.query(
                "select id, user_id, content, tags, source, platform_message_id, report_config_ids, reviewed_at, created_by, created_at, updated_by, updated_at, deleted " +
                        "from inspiration_notes where user_id = ? and deleted = 0 order by created_at desc, id desc",
                noteMapper(), userId
        );
    }

    public List<InspirationNote> findByUserIdAndTags(Long userId, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return findByUserId(userId);
        }
        return findByUserId(userId).stream()
                .filter(note -> note.getTags() != null && note.getTags().stream().anyMatch(tags::contains))
                .toList();
    }

    public List<InspirationNote> findByUserIdAndDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return jdbcTemplate.query(
                "select id, user_id, content, tags, source, platform_message_id, report_config_ids, reviewed_at, created_by, created_at, updated_by, updated_at, deleted " +
                        "from inspiration_notes where user_id = ? and deleted = 0 and created_at::date between ? and ? order by created_at desc, id desc",
                noteMapper(), userId, java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate)
        );
    }

    public List<InspirationNote> findUnreviewedByUserId(Long userId, int limit) {
        return jdbcTemplate.query(
                "select id, user_id, content, tags, source, platform_message_id, report_config_ids, reviewed_at, created_by, created_at, updated_by, updated_at, deleted " +
                        "from inspiration_notes where user_id = ? and deleted = 0 and reviewed_at is null " +
                        "order by created_at asc, id asc limit ?",
                noteMapper(), userId, limit
        );
    }

    public void softDeleteById(Long id, Long updatedBy) {
        jdbcTemplate.update(
                "update inspiration_notes set deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                updatedBy, id
        );
    }

    private RowMapper<InspirationNote> noteMapper() {
        return (rs, rowNum) -> mapNote(rs);
    }

    private InspirationNote mapNote(ResultSet rs) throws SQLException {
        InspirationNote note = new InspirationNote();
        note.setId(rs.getLong("id"));
        note.setUserId(rs.getLong("user_id"));
        note.setContent(rs.getString("content"));
        note.setTags(toStringList(rs.getArray("tags")));
        note.setSource(rs.getString("source"));
        note.setPlatformMessageId(rs.getString("platform_message_id"));
        note.setReportConfigIds(toLongList(rs.getArray("report_config_ids")));
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        note.setReviewedAt(reviewedAt == null ? null : reviewedAt.toInstant().atOffset(java.time.ZoneOffset.UTC));
        note.setCreatedBy(rs.getObject("created_by", Long.class));
        note.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        note.setUpdatedBy(rs.getObject("updated_by", Long.class));
        note.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        note.setDeleted(rs.getInt("deleted"));
        return note;
    }

    private Array toSqlArray(java.sql.Connection connection, String typeName, List<?> values) throws SQLException {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return connection.createArrayOf(typeName, values.toArray());
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] arr = (Object[]) array.getArray();
        return Arrays.stream(arr)
                .map(Object::toString)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<Long> toLongList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] arr = (Object[]) array.getArray();
        return Arrays.stream(arr)
                .map(o -> ((Number) o).longValue())
                .collect(Collectors.toList());
    }
}
