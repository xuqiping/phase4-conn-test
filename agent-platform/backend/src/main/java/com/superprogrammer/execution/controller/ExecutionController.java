// agent-platform/backend/src/main/java/com/superprogrammer/execution/controller/ExecutionController.java
package com.superprogrammer.execution.controller;

import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.execution.entity.ExecutionLog;
import com.superprogrammer.execution.service.ExecutionLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final ExecutionLogService executionLogService;

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

    /**
     * 查询执行详情
     */
    @GetMapping("/{id}")
    @RequirePermission("execution:read")
    public ResponseEntity<R<ExecutionLog>> getExecution(@PathVariable Long id) {
        ExecutionLog log = executionLogService.getExecutionLog(id);
        return ResponseEntity.ok(R.ok(log));
    }

    /**
     * 按工作流ID查询执行列表
     */
    @GetMapping
    @RequirePermission("execution:read")
    public ResponseEntity<R<List<ExecutionLog>>> listByWorkflow(
            @RequestParam Long workflowId) {
        List<ExecutionLog> logs = executionLogService.listByWorkflowId(workflowId);
        return ResponseEntity.ok(R.ok(logs));
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }
}
