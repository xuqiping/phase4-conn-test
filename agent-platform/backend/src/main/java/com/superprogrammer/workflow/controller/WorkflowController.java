package com.superprogrammer.workflow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import com.superprogrammer.runtime.dto.ExecutionEvent;
import com.superprogrammer.runtime.service.RuntimeExecutionService;
import com.superprogrammer.workflow.dto.WorkflowCreateRequest;
import com.superprogrammer.workflow.dto.WorkflowDetailVO;
import com.superprogrammer.workflow.dto.WorkflowVO;
import com.superprogrammer.workflow.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;
    private final RuntimeExecutionService runtimeExecutionService;
    private final com.superprogrammer.workflow.service.WorkflowKbBindingService workflowKbBindingService;

    @GetMapping
    @RequirePermission("workflow:read")
    public ResponseEntity<R<List<WorkflowVO>>> listWorkflows() {
        Long userId = getCurrentUserId();
        List<WorkflowVO> workflows = workflowService.listWorkflows(userId);
        return ResponseEntity.ok(R.ok(workflows));
    }

    @PostMapping
    @RequirePermission("workflow:create")
    public ResponseEntity<R<WorkflowVO>> createWorkflow(@Valid @RequestBody WorkflowCreateRequest request) {
        Long userId = getCurrentUserId();
        WorkflowVO workflow = workflowService.createWorkflow(request, userId);
        return ResponseEntity.ok(R.ok("创建成功", workflow));
    }

    @GetMapping("/{id}")
    @RequirePermission("workflow:read")
    public ResponseEntity<R<WorkflowDetailVO>> getWorkflowDetail(@PathVariable Long id) {
        WorkflowDetailVO detail = workflowService.getWorkflowDetail(id, getCurrentUserId(), isCurrentUserAdmin());
        return ResponseEntity.ok(R.ok(detail));
    }

    @PutMapping("/{id}")
    @RequirePermission("workflow:update")
    public ResponseEntity<R<WorkflowVO>> updateWorkflow(
            @PathVariable Long id,
            @Valid @RequestBody WorkflowCreateRequest request) {
        Long userId = getCurrentUserId();
        WorkflowVO workflow = workflowService.updateWorkflow(id, request, userId, isCurrentUserAdmin());
        return ResponseEntity.ok(R.ok("更新成功", workflow));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("workflow:delete")
    public ResponseEntity<R<Void>> deleteWorkflow(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        workflowService.deleteWorkflow(id, userId);
        return ResponseEntity.ok(R.ok("删除成功", null));
    }

    @PostMapping("/{id}/duplicate")
    @RequirePermission("workflow:create")
    public ResponseEntity<R<WorkflowVO>> duplicateWorkflow(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        WorkflowVO workflow = workflowService.duplicateWorkflow(id, userId);
        return ResponseEntity.ok(R.ok("复制成功", workflow));
    }

    @PostMapping("/{id}/run")
    @RequirePermission("execution:run")
    @com.superprogrammer.common.ratelimit.RateLimit(action = "workflow_run", max = 10, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public ResponseEntity<R<List<ExecutionEvent>>> runWorkflow(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> input) {
        Long userId = getCurrentUserId();
        List<ExecutionEvent> events = runtimeExecutionService.runWorkflow(
                        id,
                        userId,
                        input == null ? Map.of() : input)
                .collectList()
                .block();
        return ResponseEntity.ok(R.ok("工作流运行完成", events));
    }

    @PostMapping(value = "/{id}/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission("execution:run")
    @com.superprogrammer.common.ratelimit.RateLimit(action = "workflow_run", max = 10, windowSeconds = 60,
            algo = com.superprogrammer.common.ratelimit.RateLimit.RateLimitAlgo.SLIDING)
    public SseEmitter streamRunWorkflow(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> input) {
        Long userId = getCurrentUserId();
        SseEmitter emitter = new SseEmitter(0L);
        runtimeExecutionService.runWorkflow(
                id,
                userId,
                input == null ? Map.of() : input)
                .subscribe(
                        event -> sendRuntimeEvent(emitter, event),
                        emitter::completeWithError,
                        emitter::complete);
        return emitter;
    }

    @GetMapping("/{id}/export")
    @RequirePermission("workflow:read")
    public ResponseEntity<R<WorkflowDetailVO>> exportWorkflow(@PathVariable Long id) {
        WorkflowDetailVO detail = workflowService.getWorkflowDetail(id, getCurrentUserId(), isCurrentUserAdmin());
        return ResponseEntity.ok(R.ok(detail));
    }

    @PostMapping("/import")
    @RequirePermission("workflow:create")
    public ResponseEntity<R<WorkflowVO>> importWorkflow(@Valid @RequestBody WorkflowCreateRequest request) {
        Long userId = getCurrentUserId();
        WorkflowVO workflow = workflowService.createWorkflow(request, userId);
        return ResponseEntity.ok(R.ok("导入成功", workflow));
    }

    // ---- KB 检索范围绑定（阶段5 RAG scope）----

    @GetMapping("/{id}/kb-bindings")
    @RequirePermission("workflow:read")
    public ResponseEntity<R<List<com.superprogrammer.workflow.dto.WorkflowKbBindingVO>>> listWorkflowKbBindings(@PathVariable Long id) {
        return ResponseEntity.ok(R.ok(workflowKbBindingService.listBindings(id, getCurrentUserId(), isCurrentUserAdmin())));
    }

    @PutMapping("/{id}/kb-bindings")
    @RequirePermission("workflow:update")
    public ResponseEntity<R<Void>> saveWorkflowKbBindings(
            @PathVariable Long id,
            @RequestBody List<Long> kbIds) {
        workflowKbBindingService.saveBindings(id, kbIds, getCurrentUserId(), isCurrentUserAdmin());
        return ResponseEntity.ok(R.ok("Workflow KB 绑定保存成功", null));
    }

    /** Workflow 级记忆模式开关（V26，写 workflow.rag_enabled）。body={"enabled":true/false/null}。 */
    @PutMapping("/{id}/rag-enabled")
    @RequirePermission("workflow:update")
    public ResponseEntity<R<Void>> setWorkflowRagEnabled(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body == null ? null : body.get("enabled");
        workflowKbBindingService.setRagEnabled(id, enabled, getCurrentUserId(), isCurrentUserAdmin());
        return ResponseEntity.ok(R.ok("Workflow 记忆模式开关已更新", null));
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (Long) authentication.getPrincipal();
    }

    private boolean isCurrentUserAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> "ROLE_admin".equalsIgnoreCase(authority) || "ROLE_ADMIN".equalsIgnoreCase(authority));
    }

    private void sendRuntimeEvent(SseEmitter emitter, ExecutionEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.getExecutionId() + ":" + event.getType() + ":" + (event.getNodeId() == null ? "" : event.getNodeId()))
                    .name(event.getType())
                    .data(event, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
