package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryGenMatrixItemVO;
import com.superprogrammer.chat.service.internal.MemoryGenConfigService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.R;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计划12 · F · gen 开关矩阵端点（总体设计 §3.1 + §5 开关矩阵）。
 * <p>
 * 前端 {@code RagMemorySettingsTab} gen 矩阵 UI 用：列出我所在项目的 owner 项目级开关 + 本人会员覆写开关，
 * 按角色 owner 开关可编辑性区分（仅 OWNER 可改 owner 开关；会员覆写本人可改）。一键关 = 前端循环 PUT member。
 *
 * @see MemoryGenConfigService 矩阵读写 + 权边界
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory/gen-matrix")
@RequiredArgsConstructor
public class MemoryGenConfigController {

    private final MemoryGenConfigService genConfigService;

    /** 当前用户 gen 矩阵（我所在的 ACTIVE 项目 + 双开关 + effective）。 */
    @GetMapping
    public ResponseEntity<R<List<MemoryGenMatrixItemVO>>> matrix() {
        Long uid = requireLogin();
        return ResponseEntity.ok(R.ok(genConfigService.getMatrix(uid)));
    }

    /** 设 owner 项目级开关（仅项目 OWNER）。 */
    @PutMapping("/projects/{projectId}/owner")
    @AuditLog(module = "memory", action = "gen_config_set", targetType = "memory_config")
    public ResponseEntity<R<Void>> putOwner(@PathVariable Long projectId,
                                            @RequestBody GenToggleRequest req) {
        Long uid = requireLogin();
        if (req == null || req.getGenEnabled() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "genEnabled 必填");
        }
        genConfigService.setOwnerToggle(uid, projectId, req.getGenEnabled());
        return ResponseEntity.ok(R.ok("已保存", null));
    }

    /** 设本人会员覆写开关（任意项目成员改自己）。 */
    @PutMapping("/projects/{projectId}/member")
    @AuditLog(module = "memory", action = "gen_config_set", targetType = "memory_config")
    public ResponseEntity<R<Void>> putMember(@PathVariable Long projectId,
                                             @RequestBody GenToggleRequest req) {
        Long uid = requireLogin();
        if (req == null || req.getGenEnabled() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "genEnabled 必填");
        }
        genConfigService.setMemberOverride(uid, projectId, req.getGenEnabled());
        return ResponseEntity.ok(R.ok("已保存", null));
    }

    @Data
    public static class GenToggleRequest {
        private Boolean genEnabled;
    }

    private Long requireLogin() {
        Long uid = getCurrentUserId();
        if (uid == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return uid;
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return null;
        }
        return (Long) auth.getPrincipal();
    }
}
