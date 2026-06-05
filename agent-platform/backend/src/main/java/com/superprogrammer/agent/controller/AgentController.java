// agent-platform/backend/src/main/java/com/superprogrammer/agent/controller/AgentController.java
package com.superprogrammer.agent.controller;

import com.superprogrammer.agent.dto.AgentCreateRequest;
import com.superprogrammer.agent.dto.AgentDetailVO;
import com.superprogrammer.agent.dto.AgentVO;
import com.superprogrammer.agent.dto.SkillDetailVO;
import com.superprogrammer.agent.dto.SkillVO;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.service.AgentService;
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
    private final MarkdownSyncService markdownSyncService;

    @GetMapping("/agents")
    @RequirePermission("agent:read")
    public ResponseEntity<R<List<AgentVO>>> listAgents(
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String keyword) {
        List<AgentVO> agents = agentService.listAgents(groupId, keyword, null);
        return ResponseEntity.ok(R.ok(agents));
    }

    @GetMapping("/agents/{id}")
    @RequirePermission("agent:read")
    public ResponseEntity<R<AgentDetailVO>> getAgentDetail(@PathVariable Long id) {
        AgentDetailVO detail = agentService.getAgentDetail(id);
        return ResponseEntity.ok(R.ok(detail));
    }

    @GetMapping("/agents/{id}/skills")
    @RequirePermission("agent:read")
    public ResponseEntity<R<List<SkillVO>>> listAgentSkills(@PathVariable Long id) {
        List<SkillVO> skills = agentService.getAgentDetail(id).getSkills();
        return ResponseEntity.ok(R.ok(skills));
    }

    @GetMapping("/skills/{id}")
    @RequirePermission("agent:read")
    public ResponseEntity<R<SkillDetailVO>> getSkillDetail(@PathVariable Long id) {
        SkillDetailVO detail = agentService.getSkillDetail(id);
        return ResponseEntity.ok(R.ok(detail));
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
}
