// agent-platform/backend/src/main/java/com/superprogrammer/auth/controller/RoleController.java
package com.superprogrammer.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.superprogrammer.auth.entity.Role;
import com.superprogrammer.auth.mapper.RoleMapper;
import com.superprogrammer.common.result.PageResult;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleMapper roleMapper;

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

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    public ResponseEntity<R<Role>> getRole(@PathVariable Long id) {
        Role role = roleMapper.selectById(id);
        return ResponseEntity.ok(R.ok(role));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('role:manage')")
    public ResponseEntity<R<Role>> createRole(@RequestBody Role role) {
        roleMapper.insert(role);
        return ResponseEntity.ok(R.ok(role));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    public ResponseEntity<R<Role>> updateRole(@PathVariable Long id, @RequestBody Role role) {
        role.setId(id);
        roleMapper.updateById(role);
        return ResponseEntity.ok(R.ok(role));
    }
}
