// agent-platform/backend/src/main/java/com/superprogrammer/agent/controller/AgentController.java
package com.superprogrammer.agent.controller;

import com.superprogrammer.common.audit.AuditLog;
import com.superprogrammer.agent.dto.AgentCreateRequest;
import com.superprogrammer.agent.dto.AgentDetailVO;
import com.superprogrammer.agent.dto.AgentVO;
import com.superprogrammer.agent.dto.SkillDetailVO;
import com.superprogrammer.agent.dto.SkillSaveRequest;
import com.superprogrammer.agent.dto.SkillVO;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.service.AgentService;
import com.superprogrammer.agent.service.AgentPermissionService;
import com.superprogrammer.agent.service.MarkdownSyncService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final AgentPermissionService agentPermissionService;
    private final com.superprogrammer.agent.service.SkillService skillService;
    private final MarkdownSyncService markdownSyncService;
    private final com.superprogrammer.agent.service.AgentKbBindingService agentKbBindingService;

    @GetMapping("/agents")
    @RequirePermission("agent:read")
    public ResponseEntity<R<List<AgentVO>>> listAgents(
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String keyword) {
        List<AgentVO> agents = agentService.listAgents(groupId, keyword, null, getOperatorId(), isAdmin());
        return ResponseEntity.ok(R.ok(agents));
    }

    @GetMapping("/agents/{id}")
    @RequirePermission("agent:read")
    public ResponseEntity<R<AgentDetailVO>> getAgentDetail(@PathVariable Long id) {
        AgentDetailVO detail = agentService.getAgentDetail(id, getOperatorId(), isAdmin());
        return ResponseEntity.ok(R.ok(detail));
    }

    @GetMapping("/agents/{id}/skills")
    @RequirePermission("agent:read")
    public ResponseEntity<R<List<SkillVO>>> listAgentSkills(@PathVariable Long id) {
        List<SkillVO> skills = agentService.getAgentDetail(id, getOperatorId(), isAdmin()).getSkills();
        return ResponseEntity.ok(R.ok(skills));
    }

    @GetMapping("/skills/{id}")
    @RequirePermission("agent:read")
    public ResponseEntity<R<SkillDetailVO>> getSkillDetail(@PathVariable Long id) {
        SkillDetailVO detail = agentService.getSkillDetail(id, getOperatorId(), isAdmin());
        return ResponseEntity.ok(R.ok(detail));
    }

    @GetMapping("/agents/{id}/access")
    @RequirePermission("agent:read")
    public ResponseEntity<R<com.superprogrammer.agent.dto.AgentAccessVO>> getAgentAccess(@PathVariable Long id) {
        Long userId = getOperatorId();
        return ResponseEntity.ok(R.ok(agentPermissionService.resolveAccess(id, userId, isAdmin())));
    }

    @GetMapping("/agents/{id}/permissions")
    @RequirePermission("agent:read")
    public ResponseEntity<R<List<com.superprogrammer.agent.dto.AgentPermissionVO>>> listAgentPermissions(@PathVariable Long id) {
        Long userId = getOperatorId();
        return ResponseEntity.ok(R.ok(agentPermissionService.listPermissions(id, userId, isAdmin())));
    }

    @PutMapping("/agents/{id}/permissions")
    @RequirePermission("agent:update")
    public ResponseEntity<R<Void>> saveAgentPermissions(
            @PathVariable Long id,
            @RequestBody List<com.superprogrammer.agent.dto.AgentPermissionSaveRequest> body) {
        Long userId = getOperatorId();
        agentPermissionService.savePermissions(id, body, userId, isAdmin());
        return ResponseEntity.ok(R.ok("Agent 权限保存成功", null));
    }

    // ---- KB 检索范围绑定（阶段5 RAG scope）----

    @GetMapping("/agents/{id}/kb-bindings")
    @RequirePermission("agent:read")
    public ResponseEntity<R<List<com.superprogrammer.agent.dto.AgentKbBindingVO>>> listAgentKbBindings(@PathVariable Long id) {
        Long userId = getOperatorId();
        return ResponseEntity.ok(R.ok(agentKbBindingService.listBindings(id, userId, isAdmin())));
    }

    @PutMapping("/agents/{id}/kb-bindings")
    @RequirePermission("agent:update")
    public ResponseEntity<R<Void>> saveAgentKbBindings(
            @PathVariable Long id,
            @RequestBody List<Long> kbIds) {
        Long userId = getOperatorId();
        agentKbBindingService.saveBindings(id, kbIds, userId, isAdmin());
        return ResponseEntity.ok(R.ok("Agent KB 绑定保存成功", null));
    }

    /** Agent 级记忆模式开关（V26，写 Agent.config ragEnabled）。body={"enabled":true/false/null}。 */
    @PutMapping("/agents/{id}/rag-enabled")
    @RequirePermission("agent:update")
    public ResponseEntity<R<Void>> setAgentRagEnabled(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        Long userId = getOperatorId();
        Boolean enabled = body == null ? null : body.get("enabled");
        agentKbBindingService.setRagEnabled(id, enabled, userId, isAdmin());
        return ResponseEntity.ok(R.ok("Agent 记忆模式开关已更新", null));
    }

    @PostMapping("/agents/{id}/copy")
    @RequirePermission("agent:read")
    public ResponseEntity<R<AgentDetailVO>> copyAgent(
            @PathVariable Long id,
            @RequestBody(required = false) com.superprogrammer.agent.dto.AgentCopyRequest body) {
        Long userId = getOperatorId();
        AgentDetailVO detail = agentService.copyAgent(id, body, userId, isAdmin());
        return ResponseEntity.ok(R.ok("Agent 复制成功", detail));
    }

    @PostMapping("/agents/{id}/skills")
    @RequirePermission("skill:manage")
    public ResponseEntity<R<SkillDetailVO>> createSkill(
            @PathVariable Long id,
            @RequestBody SkillSaveRequest body) {
        SkillDetailVO detail = skillService.createSkill(id, body, getOperatorId());
        return ResponseEntity.ok(R.ok("能力创建成功", detail));
    }

    @PutMapping("/skills/{id}")
    @RequirePermission("skill:manage")
    public ResponseEntity<R<SkillDetailVO>> updateSkill(
            @PathVariable Long id,
            @RequestBody SkillSaveRequest body) {
        SkillDetailVO detail = skillService.updateSkill(id, body, getOperatorId());
        return ResponseEntity.ok(R.ok("能力更新成功", detail));
    }

    @DeleteMapping("/skills/{id}")
    @RequirePermission("skill:manage")
    public ResponseEntity<R<Void>> deleteSkill(@PathVariable Long id) {
        skillService.deleteSkill(id);
        return ResponseEntity.ok(R.ok("能力删除成功", null));
    }

    @PostMapping("/agents")
    @RequirePermission("agent:create")
    public ResponseEntity<R<AgentVO>> createAgent(@RequestBody AgentCreateRequest body) {
        Long operatorId = getOperatorId();
        Agent agent = new Agent();
        agent.setName(body.getName());
        agent.setDescription(body.getDescription());
        agent.setAvatar(body.getAvatar());
        agent.setGroupId(body.getGroupId());
        agent.setStatus("DRAFT");
        agent.setCreatedBy(operatorId);
        agent.setUpdatedBy(operatorId);
        AgentVO vo = agentService.createAgent(agent);
        return ResponseEntity.ok(R.ok(vo));
    }

    @PutMapping("/agents/{id}")
    @RequirePermission("agent:update")
    public ResponseEntity<R<AgentVO>> updateAgent(@PathVariable Long id, @RequestBody AgentCreateRequest body) {
        Long operatorId = getOperatorId();
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName(body.getName());
        agent.setDescription(body.getDescription());
        agent.setAvatar(body.getAvatar());
        if (body.getGroupId() != null) {
            agent.setGroupId(body.getGroupId());
        }
        agent.setUpdatedBy(operatorId);
        AgentVO vo = agentService.updateAgent(agent);
        return ResponseEntity.ok(R.ok(vo));
    }

    @DeleteMapping("/agents/{id}")
    @RequirePermission("agent:delete")
    public ResponseEntity<R<Void>> deleteAgent(@PathVariable Long id) {
        agentService.deleteAgent(id);
        return ResponseEntity.ok(R.ok(null));
    }

    @PutMapping("/agents/{id}/status")
    @RequirePermission("agent:publish")
    @AuditLog(module = "agent", action = "publish", targetType = "agent")
    public ResponseEntity<R<Void>> updateAgentStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Long operatorId = getOperatorId();
        agentService.updateStatus(id, body.get("status"), operatorId);
        return ResponseEntity.ok(R.ok(null));
    }

    @PostMapping("/agents/sync")
    @RequirePermission("agent:create")
    public ResponseEntity<R<Integer>> syncFromMarkdown() {
        Long operatorId = getOperatorId();
        int count = markdownSyncService.syncAll(operatorId);
        return ResponseEntity.ok(R.ok("同步完成", count));
    }

    private Long getOperatorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (Long) auth.getPrincipal();
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_admin".equalsIgnoreCase(authority.getAuthority())
                        || "ROLE_ADMIN".equalsIgnoreCase(authority.getAuthority()));
    }
}
