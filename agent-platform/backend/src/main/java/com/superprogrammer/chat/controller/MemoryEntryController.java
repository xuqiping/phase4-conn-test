package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryProjectEntryVO;
import com.superprogrammer.chat.service.internal.MemoryEntryReviewService;
import com.superprogrammer.chat.service.internal.MemoryRosterService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 记忆二期 P1 · 收录条目端点（FR-005）。
 * <p>
 * <b>权边界</b>：
 * <ul>
 *   <li>{@code GET /projects/{pid}/entries}：项目 ACTIVE 成员；owner/admin 全量，成员仅自己产生的。</li>
 *   <li>{@code POST /entries/{id}/review}：owner/admin（service 内建校验，防漏判）；body {"action":"approve|reject"}。</li>
 *   <li>{@code DELETE /entries/{id}}：条目作者撤回自己的（service 校验作者身份）。</li>
 * </ul>
 * 审计：审核/撤回均 log.info 留痕。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory")
@RequiredArgsConstructor
public class MemoryEntryController {

    private final MemoryEntryReviewService reviewService;
    private final MemoryRosterService rosterService;

    /** 条目列表（审核页/成员「我的条目」）；status 可空=全部。 */
    @GetMapping("/projects/{projectId}/entries")
    public ResponseEntity<R<List<MemoryProjectEntryVO>>> listEntries(@PathVariable Long projectId,
                                                                     @RequestParam(required = false) String status) {
        Long uid = requireLogin();
        if (!rosterService.isMember(projectId, uid)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "非项目成员，无权查看收录条目");
        }
        return ResponseEntity.ok(R.ok(reviewService.listEntries(projectId, status, uid)));
    }

    /** 审核：approve → ACTIVE；reject → 软删 + 负例反哺（owner/admin，service 内建校验）。 */
    @PostMapping("/entries/{entryId}/review")
    public ResponseEntity<R<Void>> review(@PathVariable Long entryId,
                                          @RequestBody Map<String, String> body) {
        Long operatorId = requireLogin();
        String action = body != null ? body.get("action") : null;
        reviewService.review(entryId, action, operatorId);
        return ResponseEntity.ok(R.ok("已处理", null));
    }

    /** 作者撤回自己产生的条目（软删）。 */
    @DeleteMapping("/entries/{entryId}")
    public ResponseEntity<R<Void>> withdraw(@PathVariable Long entryId) {
        Long uid = requireLogin();
        reviewService.withdraw(entryId, uid);
        return ResponseEntity.ok(R.ok("已撤回", null));
    }

    private Long requireLogin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long uid = (auth == null || auth.getPrincipal() == null) ? null : (Long) auth.getPrincipal();
        if (uid == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return uid;
    }
}
