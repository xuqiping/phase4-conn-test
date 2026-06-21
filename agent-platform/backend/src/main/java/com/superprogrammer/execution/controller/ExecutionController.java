// agent-platform/backend/src/main/java/com/superprogrammer/execution/controller/ExecutionController.java
package com.superprogrammer.execution.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.result.R;
import com.superprogrammer.execution.entity.ExecutionLog;
import com.superprogrammer.execution.service.ExecutionLogService;
import com.superprogrammer.execution.vo.ExecutionRecoveryInfoVO;
import com.superprogrammer.runtime.dto.ExecutionEvent;
import com.superprogrammer.runtime.service.RuntimeExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final ExecutionLogService executionLogService;
    private final RuntimeExecutionService runtimeExecutionService;
    private final UserMapper userMapper;

    /**
     * 启动执行（仅写日志，实际执行引擎预留）
     */
    @PostMapping
    @RequirePermission("execution:run")
    public ResponseEntity<R<ExecutionLog>> startExecution(
            @RequestParam Long workflowId,
            @RequestParam String workflowName) {
        Long userId = getCurrentUserId();
        ExecutionLog log = executionLogService.startExecution(workflowId, workflowName, userId);
        return ResponseEntity.ok(R.ok("执行已启动", log));
    }

    @GetMapping("/pending-approvals")
    @RequirePermission("execution:read")
    public ResponseEntity<R<List<ExecutionLog>>> listPendingApprovals() {
        return ResponseEntity.ok(R.ok(executionLogService.listPendingApprovals()));
    }

    /**
     * 查询执行详情
     */
    @GetMapping("/{id}")
    @RequirePermission("execution:read")
    public ResponseEntity<R<ExecutionLog>> getExecution(@PathVariable Long id) {
        Long userScope = isCurrentUserAdmin() ? null : getCurrentUserId();
        ExecutionLog log = executionLogService.getVisibleExecutionLog(id, userScope);
        fillTriggeredByUsername(log);
        return ResponseEntity.ok(R.ok(log));
    }

    @GetMapping("/{id}/recovery")
    @RequirePermission("execution:read")
    public ResponseEntity<R<ExecutionRecoveryInfoVO>> getRecoveryInfo(@PathVariable Long id) {
        Long userScope = isCurrentUserAdmin() ? null : getCurrentUserId();
        return ResponseEntity.ok(R.ok(executionLogService.getVisibleRecoveryInfo(id, userScope)));
    }

    /**
     * 按工作流ID查询执行列表
     */
    @GetMapping
    @RequirePermission("execution:read")
    public ResponseEntity<R<List<ExecutionLog>>> listByWorkflow(
            @RequestParam(required = false) Long workflowId) {
        List<ExecutionLog> logs;
        if (workflowId != null) {
            logs = executionLogService.listByWorkflowId(workflowId);
        } else {
            Long userScope = isCurrentUserAdmin() ? null : getCurrentUserId();
            logs = executionLogService.listVisibleExecutions(userScope);
        }
        logs.forEach(this::fillTriggeredByUsername);
        return ResponseEntity.ok(R.ok(logs));
    }

    @PostMapping("/{id}/retry")
    @RequirePermission("execution:run")
    public ResponseEntity<R<List<ExecutionEvent>>> retryExecution(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        List<ExecutionEvent> events = runtimeExecutionService.retryWorkflowExecution(id, userId)
                .collectList()
                .block();
        return ResponseEntity.ok(R.ok("执行重试已完成", events));
    }

    @PostMapping("/resume")
    @RequirePermission("execution:run")
    public ResponseEntity<R<List<ExecutionEvent>>> resumeExecution(@RequestParam String checkpointRef) {
        Long userId = getCurrentUserId();
        List<ExecutionEvent> events = runtimeExecutionService.resumeWorkflowFromCheckpoint(checkpointRef, userId)
                .collectList()
                .block();
        return ResponseEntity.ok(R.ok("执行恢复已完成", events));
    }

    @PostMapping("/{id}/approve")
    @RequirePermission("execution:run")
    public ResponseEntity<R<List<ExecutionEvent>>> approveExecution(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        List<ExecutionEvent> events = runtimeExecutionService.approveWorkflowExecution(id, userId)
                .collectList()
                .block();
        return ResponseEntity.ok(R.ok("审批通过，执行已继续", events));
    }

    @PostMapping("/{id}/reject")
    @RequirePermission("execution:run")
    public ResponseEntity<R<Void>> rejectExecution(
            @PathVariable Long id,
            @RequestParam(defaultValue = "rejected") String reason) {
        executionLogService.failExecution(id, "人工审批拒绝: " + reason);
        return ResponseEntity.ok(R.ok("审批已拒绝", null));
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

    private boolean isCurrentUserAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> "ROLE_admin".equals(authority));
    }

    private void fillTriggeredByUsername(ExecutionLog log) {
        if (log == null || log.getTriggeredBy() == null) {
            return;
        }
        User user = userMapper.selectById(log.getTriggeredBy());
        if (user != null) {
            log.setTriggeredByUsername(user.getUsername());
        }
    }
}
