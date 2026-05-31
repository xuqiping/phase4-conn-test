// agent-platform/backend/src/main/java/com/superprogrammer/workflow/controller/WorkflowController.java
package com.superprogrammer.workflow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.workflow.dto.*;
import com.superprogrammer.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    /**
     * 查询当前用户的工作流列表
     */
    @GetMapping
    @RequirePermission("workflow:read")
    public ResponseEntity<R<List<WorkflowVO>>> listWorkflows() {
        Long userId = getCurrentUserId();
        List<WorkflowVO> workflows = workflowService.listWorkflows(userId);
        return ResponseEntity.ok(R.ok(workflows));
    }

    /**
     * 创建工作流
     */
    @PostMapping
    @RequirePermission("workflow:create")
    public ResponseEntity<R<WorkflowVO>> createWorkflow(
            @Valid @RequestBody WorkflowCreateRequest request) {
        Long userId = getCurrentUserId();
        WorkflowVO workflow = workflowService.createWorkflow(request, userId);
        return ResponseEntity.ok(R.ok("创建成功", workflow));
    }

    /**
     * 获取工作流详情（含nodes和edges）
     */
    @GetMapping("/{id}")
    @RequirePermission("workflow:read")
    public ResponseEntity<R<WorkflowDetailVO>> getWorkflowDetail(@PathVariable Long id) {
        WorkflowDetailVO detail = workflowService.getWorkflowDetail(id);
        return ResponseEntity.ok(R.ok(detail));
    }

    /**
     * 更新工作流（含nodes和edges）
     */
    @PutMapping("/{id}")
    @RequirePermission("workflow:update")
    public ResponseEntity<R<WorkflowVO>> updateWorkflow(
            @PathVariable Long id,
            @Valid @RequestBody WorkflowCreateRequest request) {
        Long userId = getCurrentUserId();
        WorkflowVO workflow = workflowService.updateWorkflow(id, request, userId);
        return ResponseEntity.ok(R.ok("更新成功", workflow));
    }

    /**
     * 删除工作流（逻辑删除）
     */
    @DeleteMapping("/{id}")
    @RequirePermission("workflow:delete")
    public ResponseEntity<R<Void>> deleteWorkflow(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        workflowService.deleteWorkflow(id, userId);
        return ResponseEntity.ok(R.ok("删除成功", null));
    }

    /**
     * 复制工作流
     */
    @PostMapping("/{id}/duplicate")
    @RequirePermission("workflow:create")
    public ResponseEntity<R<WorkflowVO>> duplicateWorkflow(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        WorkflowVO workflow = workflowService.duplicateWorkflow(id, userId);
        return ResponseEntity.ok(R.ok("复制成功", workflow));
    }

    /**
     * 导出工作流为JSON
     */
    @GetMapping("/{id}/export")
    @RequirePermission("workflow:read")
    public ResponseEntity<R<WorkflowDetailVO>> exportWorkflow(@PathVariable Long id) {
        WorkflowDetailVO detail = workflowService.getWorkflowDetail(id);
        return ResponseEntity.ok(R.ok(detail));
    }

    /**
     * 导入工作流（从JSON创建）
     */
    @PostMapping("/import")
    @RequirePermission("workflow:create")
    public ResponseEntity<R<WorkflowVO>> importWorkflow(
            @Valid @RequestBody WorkflowCreateRequest request) {
        Long userId = getCurrentUserId();
        WorkflowVO workflow = workflowService.createWorkflow(request, userId);
        return ResponseEntity.ok(R.ok("导入成功", workflow));
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }
}
