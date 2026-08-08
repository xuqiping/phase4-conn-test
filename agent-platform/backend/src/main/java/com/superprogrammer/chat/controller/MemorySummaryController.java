package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemorySummaryVO;
import com.superprogrammer.chat.service.internal.MemorySummaryViewService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计划12 · F · 总结列表端点（总体设计 §3.4）。
 * <p>
 * 前端「总结」页签用。总结恒只读自己（user_id=self，他人总结不可见防污染），故无 ACL 边界——
 * 当前用户即作者本人，{@code projectId} 仅作 scope 过滤（null=个人 / 非空=项目）。
 *
 * @see MemorySummaryViewService 列表读取 + tag 回填
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory/summaries")
@RequiredArgsConstructor
public class MemorySummaryController {

    private final MemorySummaryViewService summaryViewService;

    /**
     * 列当前用户的总结。
     *
     * @param projectId null/缺省 = 个人 scope；非空 = 项目 scope
     */
    @GetMapping
    public ResponseEntity<R<List<MemorySummaryVO>>> list(@RequestParam(value = "projectId", required = false) Long projectId) {
        Long uid = requireLogin();
        List<MemorySummaryVO> list = summaryViewService.listMySummaries(uid, projectId);
        return ResponseEntity.ok(R.ok(list));
    }

    /** 列项目共享总结（二期 P4，FR-301：scope_owner=PROJECT 全员可读；成员咽喉在 service 层）。 */
    @GetMapping("/project/{projectId}")
    public ResponseEntity<R<List<MemorySummaryVO>>> listProjectShared(@PathVariable Long projectId) {
        Long uid = requireLogin();
        return ResponseEntity.ok(R.ok(summaryViewService.listProjectSharedSummaries(uid, projectId)));
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
