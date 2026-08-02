package com.superprogrammer.admin.service;

import com.superprogrammer.audit.service.AdminAuditLogService;
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.common.PageResult;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.security.RefreshTokenService;
import com.superprogrammer.user.dto.UserSettingsUpdateRequest;
import com.superprogrammer.user.dto.UserSummary;
import com.superprogrammer.user.entity.User;
import com.superprogrammer.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final AdminAuditLogService auditLogService;
    private final RefreshTokenService refreshTokenService;

    public PageResult<UserSummary> list(String status, long page, long size) {
        return userRepository.list(status, page, size);
    }

    public UserSummary detail(Long userId) {
        return userRepository.requireSummaryById(userId);
    }

    @Transactional
    public UserSummary approve(Long adminUserId, Long userId, String note) {
        User user = userRepository.requireById(userId);
        if (!AuthConstants.STATUS_PENDING_REVIEW.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "只能审核待审核用户");
        }
        UserSummary summary = userRepository.updateStatus(userId, AuthConstants.STATUS_ACTIVE, adminUserId);
        auditLogService.record(adminUserId, "user.approve", "user", String.valueOf(userId), note);
        return summary;
    }

    @Transactional
    public UserSummary disable(Long adminUserId, Long userId, String note) {
        User user = userRepository.requireById(userId);
        if (AuthConstants.ROLE_SUPER_ADMIN.equals(user.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能禁用超级管理员");
        }
        UserSummary summary = userRepository.updateStatus(userId, AuthConstants.STATUS_DISABLED, adminUserId);
        refreshTokenService.deleteAllForUser(userId);
        auditLogService.record(adminUserId, "user.disable", "user", String.valueOf(userId), note);
        return summary;
    }

    @Transactional
    public UserSummary enable(Long adminUserId, Long userId, String note) {
        User user = userRepository.requireById(userId);
        if (AuthConstants.ROLE_SUPER_ADMIN.equals(user.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能通过用户启用接口修改超级管理员");
        }
        UserSummary summary = userRepository.updateStatus(userId, AuthConstants.STATUS_ACTIVE, adminUserId);
        auditLogService.record(adminUserId, "user.enable", "user", String.valueOf(userId), note);
        return summary;
    }

    @Transactional
    public UserSummary updateSettings(Long adminUserId, Long userId, UserSettingsUpdateRequest request) {
        UserSummary summary = userRepository.updateSettings(userId, request.deviceLimit(), request.offlineCacheMinutes(), adminUserId);
        auditLogService.record(adminUserId, "user.update_settings", "user", String.valueOf(userId), "deviceLimit=" + request.deviceLimit() + ", offlineCacheMinutes=" + request.offlineCacheMinutes());
        return summary;
    }
}
