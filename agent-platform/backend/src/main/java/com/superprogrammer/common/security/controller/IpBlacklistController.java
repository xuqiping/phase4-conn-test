// agent-platform/backend/src/main/java/com/superprogrammer/common/security/controller/IpBlacklistController.java
package com.superprogrammer.common.security.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import com.superprogrammer.common.security.IpBlacklistService;
import com.superprogrammer.common.security.entity.IpBlacklist;
import com.superprogrammer.common.security.mapper.IpBlacklistMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * IP 封禁管理端点（11x 加固 · P2-C6）：手动封/解/列表。security:ban:manage 权限。
 */
@RestController
@RequestMapping("/api/security/ip")
@RequiredArgsConstructor
public class IpBlacklistController {

    private final IpBlacklistService ipBlacklistService;
    private final IpBlacklistMapper ipBlacklistMapper;

    /** 手动封 IP（permanent=true 永久，否则默认 24h）。 */
    @PostMapping("/block")
    @RequirePermission("security:ban:manage")
    @AuditLog(module = "security", action = "ip_block", targetType = "ip")
    public R<Void> block(@RequestBody Map<String, Object> body) {
        String ip = (String) body.get("ip");
        String reason = (String) body.get("reason");
        boolean permanent = Boolean.TRUE.equals(body.get("permanent"));
        if (ip == null || ip.isBlank() || ip.length() > 64) {
            return R.fail(400, "IP 不能为空且 ≤64 字符");
        }
        if (reason != null && reason.length() > 128) {
            return R.fail(400, "原因长度超限（≤128 字符）");
        }
        ipBlacklistService.manualBlock(ip, reason, permanent, operator());
        return R.ok(null);
    }

    /** 解封 IP。 */
    @PostMapping("/unblock")
    @RequirePermission("security:ban:manage")
    @AuditLog(module = "security", action = "ip_unblock", targetType = "ip")
    public R<Void> unblock(@RequestBody Map<String, String> body) {
        String ip = body.get("ip");
        if (ip == null || ip.isBlank()) {
            return R.fail(400, "IP 不能为空");
        }
        ipBlacklistService.unblock(ip, operator());
        return R.ok(null);
    }

    /** 封禁列表（新在前）。 */
    @GetMapping("/list")
    @RequirePermission("security:ban:manage")
    public R<PageResult<IpBlacklist>> list(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        Page<IpBlacklist> p = ipBlacklistMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(100, Math.max(1, size))),
                new LambdaQueryWrapper<IpBlacklist>().orderByDesc(IpBlacklist::getCreatedAt));
        return R.ok(PageResult.of(p.getRecords(), p.getTotal(), page, size));
    }

    /** 当前操作人（principal=userId Long）。 */
    private String operator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Long userId
                ? String.valueOf(userId) : "unknown";
    }
}
