package com.superprogrammer.user.repository;

import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.PageResult;
import com.superprogrammer.user.dto.UserSummary;
import com.superprogrammer.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean existsByContact(String contactType, String contact) {
        String column = contactColumn(contactType);
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from users where " + column + " = ? and deleted = 0",
                Integer.class,
                contact
        );
        return count != null && count > 0;
    }

    public UserSummary insertPendingReviewUser(String email, String phone, String passwordHash) {
        boolean emailVerified = email != null;
        boolean phoneVerified = phone != null;
        jdbcTemplate.update(
                "insert into users (email, phone, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted) " +
                        "values (?, ?, ?, 'user', 'pending_review', ?, ?, 1, 0, 0, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, 0)",
                email,
                phone,
                passwordHash,
                emailVerified,
                phoneVerified
        );
        return findSummaryByContact(email != null ? "email" : "phone", email != null ? email : phone);
    }

    public UserSummary findSummaryByContact(String contactType, String contact) {
        String column = contactColumn(contactType);
        List<UserSummary> users = jdbcTemplate.query(
                "select id, email, phone, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes " +
                        "from users where " + column + " = ? and deleted = 0 order by id desc limit 1",
                userSummaryMapper(),
                contact
        );
        return users.stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    private String contactColumn(String contactType) {
        if ("email".equals(contactType)) {
            return "email";
        }
        if ("phone".equals(contactType)) {
            return "phone";
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "联系方式类型必须是 email 或 phone");
    }

    public Optional<User> findByIdentifier(String identifier) {
        List<User> users = jdbcTemplate.query(
                "select id, email, phone, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted " +
                        "from users where deleted = 0 and (email = ? or phone = ?) limit 1",
                userMapper(),
                identifier,
                identifier
        );
        return users.stream().findFirst();
    }

    public Optional<User> findById(Long id) {
        List<User> users = jdbcTemplate.query(
                "select id, email, phone, password_hash, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes, created_by, created_at, updated_by, updated_at, deleted " +
                        "from users where id = ? and deleted = 0",
                userMapper(),
                id
        );
        return users.stream().findFirst();
    }

    public User requireById(Long id) {
        return findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    public UserSummary toSummary(User user) {
        return new UserSummary(
                user.getId(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.getStatus(),
                user.getEmailVerified(),
                user.getPhoneVerified(),
                user.getDeviceLimit(),
                user.getOfflineCacheMinutes()
        );
    }

    public PageResult<UserSummary> list(String status, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (safePage - 1) * safeSize;
        String where = " where deleted = 0";
        Object[] params;
        if (status != null && !status.isBlank()) {
            where += " and status = ?";
            params = new Object[]{status, safeSize, offset};
        } else {
            params = new Object[]{safeSize, offset};
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from users" + where,
                Long.class,
                status != null && !status.isBlank() ? new Object[]{status} : new Object[]{}
        );
        List<UserSummary> records = jdbcTemplate.query(
                "select id, email, phone, role, status, email_verified, phone_verified, device_limit, offline_cache_minutes from users" + where +
                        " order by id desc limit ? offset ?",
                userSummaryMapper(),
                params
        );
        return new PageResult<>(records, total == null ? 0 : total, safePage, safeSize);
    }

    public UserSummary requireSummaryById(Long id) {
        return findById(id)
                .map(this::toSummary)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    public UserSummary updateStatus(Long id, String status, Long adminUserId) {
        int rows = jdbcTemplate.update(
                "update users set status = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                status,
                adminUserId,
                id
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return requireSummaryById(id);
    }

    public UserSummary updateSettings(Long id, Integer deviceLimit, Integer offlineCacheMinutes, Long adminUserId) {
        int rows = jdbcTemplate.update(
                "update users set device_limit = ?, offline_cache_minutes = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP where id = ? and deleted = 0",
                deviceLimit,
                offlineCacheMinutes,
                adminUserId,
                id
        );
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return requireSummaryById(id);
    }

    private RowMapper<User> userMapper() {
        return (rs, rowNum) -> {
            User user = new User();
            user.setId(rs.getLong("id"));
            user.setEmail(rs.getString("email"));
            user.setPhone(rs.getString("phone"));
            user.setPasswordHash(rs.getString("password_hash"));
            user.setRole(rs.getString("role"));
            user.setStatus(rs.getString("status"));
            user.setEmailVerified(rs.getBoolean("email_verified"));
            user.setPhoneVerified(rs.getBoolean("phone_verified"));
            user.setDeviceLimit(rs.getInt("device_limit"));
            user.setOfflineCacheMinutes(rs.getInt("offline_cache_minutes"));
            return user;
        };
    }

    private RowMapper<UserSummary> userSummaryMapper() {
        return (rs, rowNum) -> new UserSummary(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getBoolean("email_verified"),
                rs.getBoolean("phone_verified"),
                rs.getInt("device_limit"),
                rs.getInt("offline_cache_minutes")
        );
    }
}
