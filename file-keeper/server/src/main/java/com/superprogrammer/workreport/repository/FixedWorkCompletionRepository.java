package com.superprogrammer.workreport.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.workreport.entity.FixedWorkCompletion;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class FixedWorkCompletionRepository {

    private final JdbcTemplate jdbcTemplate;

    public FixedWorkCompletion upsert(FixedWorkCompletion completion) {
        Optional<FixedWorkCompletion> existing = findByItemIdAndDate(completion.getItemId(), completion.getCompletionDate());
        if (existing.isPresent()) {
            FixedWorkCompletion updated = existing.get();
            updated.setCompleted(completion.getCompleted());
            updated.setCompletedAt(completion.getCompletedAt());
            updated.setCompletionSource(completion.getCompletionSource());
            updated.setUpdatedBy(completion.getUpdatedBy());
            return update(updated);
        }
        return insert(completion);
    }

    public FixedWorkCompletion insert(FixedWorkCompletion completion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "insert into fixed_work_completions (item_id, user_id, completion_date, completed, completed_at, completion_source, created_by, created_at, updated_by, updated_at, deleted) " +
                    "values (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, 0)",
                new String[] { "id" }
            );
            ps.setLong(1, completion.getItemId());
            ps.setLong(2, completion.getUserId());
            ps.setDate(3, Date.valueOf(completion.getCompletionDate()));
            ps.setBoolean(4, completion.getCompleted() != null && completion.getCompleted());
            ps.setTimestamp(5, completion.getCompletedAt() == null ? null : Timestamp.from(completion.getCompletedAt().toInstant()));
            ps.setString(6, completion.getCompletionSource());
            ps.setObject(7, completion.getCreatedBy());
            ps.setObject(8, completion.getUpdatedBy());
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        if (generatedId == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "固定工作完成记录保存后无法获取主键");
        }
        return findById(generatedId.longValue())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "固定工作完成记录保存后无法查询"));
    }

    public FixedWorkCompletion update(FixedWorkCompletion completion) {
        jdbcTemplate.update(
                "update fixed_work_completions set completed = ?, completed_at = ?, completion_source = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP " +
                        "where id = ? and deleted = 0",
                completion.getCompleted(),
                completion.getCompletedAt() == null ? null : Timestamp.from(completion.getCompletedAt().toInstant()),
                completion.getCompletionSource(),
                completion.getUpdatedBy(),
                completion.getId()
        );
        return findById(completion.getId()).orElseThrow();
    }

    public Optional<FixedWorkCompletion> findById(Long id) {
        List<FixedWorkCompletion> results = jdbcTemplate.query(
                "select id, item_id, user_id, completion_date, completed, completed_at, completion_source, created_by, created_at, updated_by, updated_at, deleted " +
                        "from fixed_work_completions where id = ? and deleted = 0",
                completionMapper(), id
        );
        return results.stream().findFirst();
    }

    public Optional<FixedWorkCompletion> findByItemIdAndDate(Long itemId, LocalDate date) {
        List<FixedWorkCompletion> results = jdbcTemplate.query(
                "select id, item_id, user_id, completion_date, completed, completed_at, completion_source, created_by, created_at, updated_by, updated_at, deleted " +
                        "from fixed_work_completions where item_id = ? and completion_date = ? and deleted = 0",
                completionMapper(), itemId, Date.valueOf(date)
        );
        return results.stream().findFirst();
    }

    public List<FixedWorkCompletion> findByUserIdAndDate(Long userId, LocalDate date) {
        return jdbcTemplate.query(
                "select id, item_id, user_id, completion_date, completed, completed_at, completion_source, created_by, created_at, updated_by, updated_at, deleted " +
                        "from fixed_work_completions where user_id = ? and completion_date = ? and deleted = 0",
                completionMapper(), userId, Date.valueOf(date)
        );
    }

    public List<FixedWorkCompletion> findByUserIdAndDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return jdbcTemplate.query(
                "select id, item_id, user_id, completion_date, completed, completed_at, completion_source, created_by, created_at, updated_by, updated_at, deleted " +
                        "from fixed_work_completions where user_id = ? and completion_date between ? and ? and deleted = 0 and completed = true",
                completionMapper(), userId, Date.valueOf(startDate), Date.valueOf(endDate)
        );
    }

    public List<FixedWorkCompletion> findByUserIdAndDateRangeAllStatuses(Long userId, LocalDate startDate, LocalDate endDate) {
        return jdbcTemplate.query(
                "select id, item_id, user_id, completion_date, completed, completed_at, completion_source, created_by, created_at, updated_by, updated_at, deleted " +
                        "from fixed_work_completions where user_id = ? and completion_date between ? and ? and deleted = 0",
                completionMapper(), userId, Date.valueOf(startDate), Date.valueOf(endDate)
        );
    }

    public void deleteByItemId(Long itemId) {
        jdbcTemplate.update(
                "update fixed_work_completions set deleted = 1 where item_id = ? and deleted = 0",
                itemId
        );
    }

    private RowMapper<FixedWorkCompletion> completionMapper() {
        return (rs, rowNum) -> mapCompletion(rs);
    }

    private FixedWorkCompletion mapCompletion(ResultSet rs) throws SQLException {
        FixedWorkCompletion completion = new FixedWorkCompletion();
        completion.setId(rs.getLong("id"));
        completion.setItemId(rs.getLong("item_id"));
        completion.setUserId(rs.getLong("user_id"));
        completion.setCompletionDate(rs.getDate("completion_date").toLocalDate());
        completion.setCompleted(rs.getBoolean("completed"));
        Timestamp completedAt = rs.getTimestamp("completed_at");
        completion.setCompletedAt(completedAt == null ? null : completedAt.toInstant().atOffset(java.time.ZoneOffset.UTC));
        completion.setCompletionSource(rs.getString("completion_source"));
        completion.setCreatedBy(rs.getObject("created_by", Long.class));
        completion.setCreatedAt(rs.getTimestamp("created_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        completion.setUpdatedBy(rs.getObject("updated_by", Long.class));
        completion.setUpdatedAt(rs.getTimestamp("updated_at").toInstant().atOffset(java.time.ZoneOffset.UTC));
        completion.setDeleted(rs.getInt("deleted"));
        return completion;
    }
}
