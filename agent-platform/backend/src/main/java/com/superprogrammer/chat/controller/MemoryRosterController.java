package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryRosterVO;
import com.superprogrammer.chat.dto.MemoryRecallAclRequest;
import com.superprogrammer.chat.dto.MemoryRecallAclVO;
import com.superprogrammer.chat.service.internal.MemoryRecallAclConfigService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计划12 · I2 · 花名册 + ACL 配置端点（总体设计 §3.6 + §6 向量 14/15）。
 * <p>
 * <b>偏离 plan</b>：plan 列「改 MemoryController」——legacy MemoryController 是 {@code /api/chat/memories}
 * （user_memories 旧表，H 收尾 404）。新 ACL 走 {@code /api/chat/memory/projects/{pid}}（同 MemoryRecallController
 * 命名空间），独立控制器避免新旧纠缠（承 C/D/E 隔离裁决）。
 * <p>
 * <b>权边界</b>（向量 14）：
 * <ul>
 *   <li>{@code GET /roster}：项目 ACTIVE 成员可见（任意 role，召回人员多选源数据）；非成员/DEPARTED → 403。</li>
 *   <li>{@code GET /recall-acl}：仅 owner / {@code recall_admin=true} admin（配权者）；否则 403。</li>
 *   <li>{@code PUT /recall-acl}：仅 owner / {@code recall_admin=true} admin；否则 403。</li>
 * </ul>
 * <b>审计</b>（向量 15）：PUT 走 {@code created_by}=当前操作人 + log.info。
 *
 * @see MemoryRosterService 花名册读取
 * @see MemoryRecallAclConfigService ACL 配置写入 + 配权判定
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory/projects/{projectId}")
@RequiredArgsConstructor
public class MemoryRosterController {

    private final MemoryRosterService rosterService;
    private final MemoryRecallAclConfigService aclConfigService;

    /** 项目花名册（含 DEPARTED，配 ACL 矩阵/召回人员多选源数据；ACTIVE 成员可见）。 */
    @GetMapping("/roster")
    public ResponseEntity<R<List<MemoryRosterVO>>> roster(@PathVariable Long projectId) {
        Long uid = requireMember(projectId);
        List<MemoryRosterVO> roster = rosterService.getRoster(projectId);
        log.debug("roster userId={} projectId={} size={}", uid, projectId, roster.size());
        return ResponseEntity.ok(R.ok(roster));
    }

    /** 当前 ACL 授权矩阵（仅 owner / recall_admin；否则 403）。 */
    @GetMapping("/recall-acl")
    public ResponseEntity<R<List<MemoryRecallAclVO>>> getRecallAcl(@PathVariable Long projectId) {
        requireConfigurable(projectId);
        return ResponseEntity.ok(R.ok(aclConfigService.getMatrix(projectId)));
    }

    /** 全量替换某 reader 的 target 集（仅 owner / recall_admin；否则 403；created_by 审计）。 */
    @PutMapping("/recall-acl")
    public ResponseEntity<R<Integer>> putRecallAcl(@PathVariable Long projectId,
                                                   @RequestBody MemoryRecallAclRequest req) {
        Long operatorId = requireConfigurable(projectId);
        Long readerUserId = req == null ? null : req.getReaderUserId();
        if (readerUserId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "readerUserId 必填");
        }
        int written = aclConfigService.replaceAll(projectId, readerUserId, req, operatorId);
        log.info("recall-acl 配置 operatorId={} projectId={} readerUserId={} written={}",
                operatorId, projectId, readerUserId, written);
        return ResponseEntity.ok(R.ok("已保存", written));
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

    /** 要求调用者有 ACL 配置权（owner / recall_admin），返其 uid；否则 403。 */
    private Long requireConfigurable(Long projectId) {
        Long uid = requireLogin();
        if (!aclConfigService.isConfigurable(projectId, uid)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅 owner 或 recall_admin 可配置 ACL");
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
