package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryRosterVO;
import com.superprogrammer.chat.service.internal.MemoryRosterService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计划12 · I2 · 花名册端点（总体设计 §3.6）。
 * <p>
 * <b>记忆二期 P1 变更</b>：{@code GET/PUT /recall-acl} 端点下线（FR-006，一期 reader×target ACL 矩阵废弃
 * —— 二期项目记忆=蒸馏条目，成员即可读，读权由成员身份判定；表随 V67 DROP）。
 * 本控制器只剩花名册（召回人员多选源数据）。
 * <p>
 * <b>权边界</b>：{@code GET /roster} 项目 ACTIVE 成员可见（任意 role）；非成员/DEPARTED → 403。
 *
 * @see MemoryRosterService 花名册读取
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory/projects/{projectId}")
@RequiredArgsConstructor
public class MemoryRosterController {

    private final MemoryRosterService rosterService;

    /** 项目花名册（含 DEPARTED，召回人员多选源数据；ACTIVE 成员可见）。 */
    @GetMapping("/roster")
    public ResponseEntity<R<List<MemoryRosterVO>>> roster(@PathVariable Long projectId) {
        Long uid = requireMember(projectId);
        List<MemoryRosterVO> roster = rosterService.getRoster(projectId);
        log.debug("roster userId={} projectId={} size={}", uid, projectId, roster.size());
        return ResponseEntity.ok(R.ok(roster));
    }

    // ---- 权边界 helpers ----

    /** 要求调用者是项目 ACTIVE 成员（roster 可见性），返其 uid；否则 403。 */
    private Long requireMember(Long projectId) {
        Long uid = requireLogin();
        if (!rosterService.isMember(projectId, uid)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非项目成员，无权查看花名册");
        }
        return uid;
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
