package com.superprogrammer.user.service;

import com.superprogrammer.admin.service.AdminUserService;
import com.superprogrammer.audit.service.AdminAuditLogService;
import com.superprogrammer.common.BusinessException;
import com.superprogrammer.common.ErrorCode;
import com.superprogrammer.security.AuthConstants;
import com.superprogrammer.user.dto.GrantEntitlementRequest;
import com.superprogrammer.user.dto.ModuleEntitlementDto;
import com.superprogrammer.user.dto.UpdateEntitlementRequest;
import com.superprogrammer.user.repository.EntitlementRepository;
import com.superprogrammer.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EntitlementService {

    private static final Set<String> VALID_MODULES = Set.of(
            AuthConstants.MODULE_FILES,
            AuthConstants.MODULE_PROCESSES,
            AuthConstants.MODULE_CLIPBOARD,
            AuthConstants.MODULE_WORK_REPORT
    );

    private final EntitlementRepository entitlementRepository;
    private final UserRepository userRepository;
    private final AdminAuditLogService auditLogService;

    public List<ModuleEntitlementDto> listByUserId(Long userId) {
        return entitlementRepository.findByUserId(userId);
    }

    public List<ModuleEntitlementDto> listActiveByUserId(Long userId) {
        return entitlementRepository.findActiveByUserId(userId);
    }

    public ModuleEntitlementDto grant(Long adminUserId, Long userId, GrantEntitlementRequest request) {
        validateModuleCode(request.moduleCode());
        userRepository.requireById(userId);
        Optional<Long> deletedId = entitlementRepository.findDeletedIdByUserAndModule(userId, request.moduleCode());
        if (deletedId.isPresent()) {
            ModuleEntitlementDto dto = entitlementRepository.restore(deletedId.get(), request.expiresAt());
            auditLogService.record(adminUserId, "entitlement.grant", "entitlement", String.valueOf(dto.id()),
                    "恢复并授予模块 " + request.moduleCode());
            return dto;
        }
        if (entitlementRepository.existsByUserAndModule(userId, request.moduleCode())) {
            throw new BusinessException(ErrorCode.CONFLICT, "该用户已拥有此模块权益");
        }
        ModuleEntitlementDto dto = entitlementRepository.insert(userId, request.moduleCode(), request.expiresAt());
        auditLogService.record(adminUserId, "entitlement.grant", "entitlement", String.valueOf(dto.id()),
                "授予模块 " + request.moduleCode());
        return dto;
    }

    public ModuleEntitlementDto update(Long adminUserId, Long userId, Long entitlementId, UpdateEntitlementRequest request) {
        ModuleEntitlementDto existing = requireEntitlementBelongsToUser(entitlementId, userId);
        ModuleEntitlementDto updated = entitlementRepository.update(entitlementId, request.enabled(), request.expiresAt());
        auditLogService.record(adminUserId, "entitlement.update", "entitlement", String.valueOf(entitlementId),
                "更新模块 " + existing.moduleCode());
        return updated;
    }

    public void revoke(Long adminUserId, Long userId, Long entitlementId) {
        ModuleEntitlementDto existing = requireEntitlementBelongsToUser(entitlementId, userId);
        entitlementRepository.softDeleteById(entitlementId);
        auditLogService.record(adminUserId, "entitlement.revoke", "entitlement", String.valueOf(entitlementId),
                "撤销模块 " + existing.moduleCode());
    }

    private void validateModuleCode(String moduleCode) {
        if (!VALID_MODULES.contains(moduleCode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模块代码必须是 files、processes、clipboard 或 work-report");
        }
    }

    private ModuleEntitlementDto requireEntitlementBelongsToUser(Long entitlementId, Long userId) {
        ModuleEntitlementDto dto = entitlementRepository.findById(entitlementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "权益记录不存在"));
        if (!dto.userId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "权益记录不存在");
        }
        return dto;
    }
}
