package com.superprogrammer.auth.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.Permission;
import com.superprogrammer.auth.entity.Role;
import com.superprogrammer.auth.entity.RolePermission;
import com.superprogrammer.auth.mapper.PermissionMapper;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.auth.mapper.RolePermissionMapper;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;

    @GetMapping
    @PreAuthorize("hasAuthority('role:manage')")
    public ResponseEntity<R<PageResult<Role>>> listRoles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<Role> rolePage = roleMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Role>().orderByAsc(Role::getId)
        );
        PageResult<Role> result = PageResult.of(
                rolePage.getRecords(), rolePage.getTotal(), page, size);
        return ResponseEntity.ok(R.ok(result));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('role:manage')")
    public ResponseEntity<R<List<Role>>> listAllRoles() {
        List<Role> roles = roleMapper.selectList(
                new LambdaQueryWrapper<Role>().orderByAsc(Role::getId));
        return ResponseEntity.ok(R.ok(roles));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    public ResponseEntity<R<Role>> getRole(@PathVariable Long id) {
        Role role = roleMapper.selectById(id);
        return ResponseEntity.ok(R.ok(role));
    }

    @GetMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('role:manage')")
    public ResponseEntity<R<List<Long>>> getRolePermissions(@PathVariable Long id) {
        List<RolePermission> rps = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, id));
        List<Long> permissionIds = rps.stream().map(RolePermission::getPermissionId).toList();
        return ResponseEntity.ok(R.ok(permissionIds));
    }

    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('role:manage')")
    @Transactional
    @AuditLog(module = "role", action = "update_permissions", targetType = "role")
    public ResponseEntity<R<Void>> updateRolePermissions(
            @PathVariable Long id,
            @RequestBody List<Long> permissionIds) {
        // 删除旧的权限关联
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, id));
        // 插入新的权限关联
        for (Long permId : permissionIds) {
            rolePermissionMapper.insert(new RolePermission(id, permId));
        }
        return ResponseEntity.ok(R.ok(null));
    }

    @GetMapping("/permissions/all")
    @PreAuthorize("hasAuthority('role:manage')")
    public ResponseEntity<R<List<Permission>>> listAllPermissions() {
        List<Permission> permissions = permissionMapper.selectList(
                new LambdaQueryWrapper<Permission>().orderByAsc(Permission::getId));
        return ResponseEntity.ok(R.ok(permissions));
    }
}
