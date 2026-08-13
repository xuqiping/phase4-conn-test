// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/UserController.java
package com.superprogrammer.auth.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.dto.UserVO;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.entity.UserRole;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.mapper.UserRoleMapper;
import com.superprogrammer.auth.service.AuthService;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import com.superprogrammer.common.security.BanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final AuthService authService;
    private final BanService banService;

    /** 11x 加固 P1-C3：合法状态白名单（堵裸写——原实现 body.get("status") 直写库）。 */
    private static final Set<String> ALLOWED_STATUS = Set.of("ACTIVE", "DISABLED", "LOCKED", "BANNED");
    /** 封号/禁用/锁定原因长度上限（对齐 V104 ban_reason VARCHAR(128)）。 */
    private static final int REASON_MAX_LEN = 128;

    @GetMapping
    @PreAuthorize("hasAuthority('user:manage')")
    public ResponseEntity<R<PageResult<UserVO>>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<User> userPage = userMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<User>().orderByDesc(User::getCreatedAt)
        );

        var vos = userPage.getRecords().stream().map(user ->
                authService.getCurrentUser(user.getId())
        ).toList();

        PageResult<UserVO> result = PageResult.of(
                vos, userPage.getTotal(), page, size);
        return ResponseEntity.ok(R.ok(result));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:manage')")
    public ResponseEntity<R<UserVO>> getUser(@PathVariable Long id) {
        UserVO userVO = authService.getCurrentUser(id);
        return ResponseEntity.ok(R.ok(userVO));
    }

    /**
     * 变更用户状态（11x 加固 P1-C3 改造）。
     *
     * <p>防护：枚举白名单（堵裸写）/防自封（平台锁死）/防最后超管（最后一名 ACTIVE 且持
     * user:manage 的用户不可被封禁）/原因长度限。
     * <p>即时生效：非 ACTIVE → BanService.revoke（删会话+ban 标记，下一请求 401）；
     * ACTIVE → BanService.restore（删标记，用户须重登）。
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('user:manage')")
    @Transactional
    @AuditLog(module = "user", action = "update_status", targetType = "user")
    public ResponseEntity<R<Void>> updateUserStatus(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, String> body) {
        String status = body.get("status");
        String reason = body.get("reason");
        if (status == null || !ALLOWED_STATUS.contains(status)) {
            return ResponseEntity.badRequest()
                    .body(R.fail(400, "非法状态，仅支持 ACTIVE/DISABLED/LOCKED/BANNED"));
        }
        if (reason != null && reason.length() > REASON_MAX_LEN) {
            return ResponseEntity.badRequest()
                    .body(R.fail(400, "原因长度超限（≤" + REASON_MAX_LEN + " 字符）"));
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            return ResponseEntity.status(404).body(R.fail(404, "用户不存在"));
        }
        if (id.equals(currentOperatorId())) {
            return ResponseEntity.status(403).body(R.fail(403, "不能变更自己的账号状态"));
        }
        // 防最后超管锁死：目标当前 ACTIVE + 持 user:manage + 全平台仅剩这 1 名 → 拒绝
        if (!"ACTIVE".equals(status) && "ACTIVE".equals(user.getStatus())
                && userMapper.selectPermissionCodesByUserId(id).contains("user:manage")
                && userMapper.countActiveByPermission("user:manage") <= 1) {
            return ResponseEntity.status(403)
                    .body(R.fail(403, "平台至少保留一名可用的用户管理员，该用户不可被封禁/禁用/锁定"));
        }
        user.setStatus(status);
        user.setBanReason("ACTIVE".equals(status) ? null : reason);
        if ("ACTIVE".equals(status)) {
            user.setLockedUntil(null); // 手动启用同时清自动锁定到期
        }
        userMapper.updateById(user);
        if ("ACTIVE".equals(status)) {
            banService.restore(id);
        } else {
            banService.revoke(id, status);
        }
        return ResponseEntity.ok(R.ok(null));
    }

    /** 当前操作人 userId（JwtAuthenticationFilter 已把 principal 设为 Long）。 */
    private Long currentOperatorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:manage')")
    @Transactional
    @AuditLog(module = "user", action = "assign_roles", targetType = "user")
    public ResponseEntity<R<Void>> assignRoles(
            @PathVariable Long id,
            @RequestBody List<Long> roleIds) {
        User user = userMapper.selectById(id);
        if (user == null) {
            return ResponseEntity.status(404).body(R.fail(404, "用户不存在"));
        }
        // 删除旧的角色关联
        userRoleMapper.delete(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, id));
        // 插入新的角色关联
        for (Long roleId : roleIds) {
            userRoleMapper.insert(new UserRole(id, roleId));
        }
        return ResponseEntity.ok(R.ok(null));
    }
}
