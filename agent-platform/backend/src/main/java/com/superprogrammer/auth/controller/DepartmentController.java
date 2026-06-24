package com.superprogrammer.auth.controller;

import com.superprogrammer.auth.dto.DepartmentRequest;
import com.superprogrammer.auth.dto.DepartmentVO;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.auth.service.DepartmentService;
import com.superprogrammer.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @GetMapping
    public ResponseEntity<R<List<DepartmentVO>>> list() {
        return ResponseEntity.ok(R.ok(departmentService.list()));
    }

    @PostMapping
    @RequirePermission("role:manage")
    public ResponseEntity<R<DepartmentVO>> create(@Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.ok(R.ok("创建成功",
                departmentService.create(request, getCurrentUserId())));
    }

    @PutMapping("/{id}")
    @RequirePermission("role:manage")
    public ResponseEntity<R<DepartmentVO>> update(@PathVariable Long id,
                                                  @Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.ok(R.ok(departmentService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("role:manage")
    public ResponseEntity<R<Void>> delete(@PathVariable Long id) {
        departmentService.delete(id);
        return ResponseEntity.ok(R.ok("删除成功", null));
    }

    @PostMapping("/members")
    @RequirePermission("role:manage")
    public ResponseEntity<R<Void>> assignMember(@RequestParam Long userId,
                                                @RequestParam Long departmentId,
                                                @RequestParam(defaultValue = "false") boolean isPrimary) {
        departmentService.assignMember(userId, departmentId, isPrimary, getCurrentUserId());
        return ResponseEntity.ok(R.ok("分配成功", null));
    }

    @DeleteMapping("/members")
    @RequirePermission("role:manage")
    public ResponseEntity<R<Void>> removeMember(@RequestParam Long userId,
                                                @RequestParam Long departmentId) {
        departmentService.removeMember(userId, departmentId);
        return ResponseEntity.ok(R.ok("移除成功", null));
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (Long) auth.getPrincipal();
    }
}
