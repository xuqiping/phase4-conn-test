// agent-platform/backend/src/main/java/com/superprogrammer/common/security/controller/SecurityEventController.java
package com.superprogrammer.common.security.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import com.superprogrammer.common.security.entity.SecurityEvent;
import com.superprogrammer.common.security.mapper.SecurityEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全事件查询/处置端点（11x 加固）：列表筛选 + ACK。security:event:read / security:ban:manage。
 */
@RestController
@RequestMapping("/api/security/events")
@RequiredArgsConstructor
public class SecurityEventController {

    private final SecurityEventMapper securityEventMapper;

    /** 事件列表（新在前；eventType/severity/handled 可选筛选，#{} 参数化防注入）。 */
    @GetMapping
    @RequirePermission("security:event:read")
    public R<PageResult<SecurityEvent>> list(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int size,
                                             @RequestParam(required = false) String eventType,
                                             @RequestParam(required = false) String severity,
                                             @RequestParam(required = false) Boolean handled) {
        LambdaQueryWrapper<SecurityEvent> wrapper = new LambdaQueryWrapper<SecurityEvent>()
                .eq(eventType != null && !eventType.isBlank(), SecurityEvent::getEventType, eventType)
                .eq(severity != null && !severity.isBlank(), SecurityEvent::getSeverity, severity)
                .eq(handled != null, SecurityEvent::getHandled, handled)
                .orderByDesc(SecurityEvent::getCreatedAt);
        Page<SecurityEvent> p = securityEventMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))), wrapper);
        return R.ok(PageResult.of(p.getRecords(), p.getTotal(), page, size));
    }

    /** 未处置计数（侧栏 badge 轮询用）。 */
    @GetMapping("/unhandled-count")
    @RequirePermission("security:event:read")
    public R<Long> unhandledCount() {
        return R.ok(securityEventMapper.selectCount(
                new LambdaQueryWrapper<SecurityEvent>().eq(SecurityEvent::getHandled, false)));
    }

    /** ACK 处置（条件更新幂等：并发 ack 只中一次）。 */
    @PostMapping("/{id}/ack")
    @RequirePermission("security:ban:manage")
    @AuditLog(module = "security", action = "event_ack", targetType = "security_event")
    public R<Void> ack(@PathVariable Long id) {
        int updated = securityEventMapper.ackIfUnhandled(id, operator());
        if (updated == 0) {
            return R.fail(409, "事件不存在或已被处置");
        }
        return R.ok(null);
    }

    /** 24h 统计（风险大盘）：总数 + 未处置 + 严重度分布 + 类型 TOP10。 */
    @GetMapping("/stats")
    @RequirePermission("security:event:read")
    public R<Map<String, Object>> stats() {
        Map<String, Object> result = new HashMap<>();
        result.put("bySeverity", securityEventMapper.countBySeverity24h());
        result.put("byType", securityEventMapper.countByType24h());
        result.put("unhandled", securityEventMapper.selectCount(
                new LambdaQueryWrapper<SecurityEvent>().eq(SecurityEvent::getHandled, false)));
        return R.ok(result);
    }

    /**
     * 批量物理删除（运维可删，P4-C12）。security_events 无 @TableLogic → deleteBatchIds 即物理删。
     * 上限 200/次；@AuditLog 留删行审计（前端二次确认）。
     */
    @DeleteMapping("/batch")
    @RequirePermission("security:ban:manage")
    @AuditLog(module = "security", action = "event_batch_delete", targetType = "security_event")
    public R<Integer> batchDelete(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return R.fail(400, "ids 不能为空");
        }
        if (ids.size() > 200) {
            return R.fail(400, "单次最多删除 200 条");
        }
        return R.ok(securityEventMapper.deleteBatchIds(ids));
    }

    private String operator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Long userId
                ? String.valueOf(userId) : "unknown";
    }
}
