package com.superprogrammer.chat.controller;

import com.superprogrammer.chat.dto.MemoryNotificationVO;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.service.internal.MemoryNotificationService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计划12 · F · 波及通知端点（总体设计 §3.8）。
 * <p>
 * <b>偏离 plan</b>：plan F 列「3s 轮询 {@code /status} 复用旧栈」——实际旧栈 {@code /memories/status}
 * 只回生成/抽取处理态，不含跨用户波及通知（V47 {@code memory_notifications} 表）。波及通知是计划12 新表，
 * 旧栈读不到。裁决：新建独立 {@code /api/chat/memory/notifications} 控制器（承 C/D/E/I2 隔离先例）。
 * <p>
 * <b>权边界</b>：仅接收者可读自己通知 + ACK（{@code user_id}=当前用户）。
 *
 * @see MemoryNotificationService 通知读取 + ACK
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/memory/notifications")
@RequiredArgsConstructor
public class MemoryNotificationController {

    private final MemoryNotificationService notificationService;

    /** 列出当前用户未处理波及通知（badge 点开详情）。 */
    @GetMapping
    public ResponseEntity<R<List<MemoryNotificationVO>>> list() {
        Long uid = requireLogin();
        List<MemoryNotificationVO> vos = notificationService.listUnresolved(uid).stream()
                .map(MemoryNotificationController::toVO)
                .toList();
        return ResponseEntity.ok(R.ok(vos));
    }

    /** 未处理通知计数（波及 badge 3s 轮询）。 */
    @GetMapping("/count")
    public ResponseEntity<R<Integer>> count() {
        Long uid = requireLogin();
        return ResponseEntity.ok(R.ok(notificationService.countUnresolved(uid)));
    }

    /** ACK 单条通知（置 resolved_at=now）；他人通知 → 403。 */
    @PostMapping("/{id}/ack")
    public ResponseEntity<R<Void>> ack(@PathVariable Long id) {
        Long uid = requireLogin();
        notificationService.ack(uid, id);
        return ResponseEntity.ok(R.ok("已处理", null));
    }

    private static MemoryNotificationVO toVO(MemoryNotification n) {
        MemoryNotificationVO vo = new MemoryNotificationVO();
        vo.setId(n.getId());
        vo.setType(n.getType());
        vo.setRefId(n.getRefId());
        vo.setMessage(n.getMessage());
        vo.setCreatedAt(n.getCreatedAt());
        return vo;
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
