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
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final AuthService authService;

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

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('user:manage')")
    @Transactional
    @AuditLog(module = "user", action = "update_status", targetType = "user")
    public ResponseEntity<R<Void>> updateUserStatus(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, String> body) {
        User user = userMapper.selectById(id);
        if (user == null) {
            return ResponseEntity.status(404).body(R.fail(404, "用户不存在"));
        }
        user.setStatus(body.get("status"));
        userMapper.updateById(user);
        return ResponseEntity.ok(R.ok(null));
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
