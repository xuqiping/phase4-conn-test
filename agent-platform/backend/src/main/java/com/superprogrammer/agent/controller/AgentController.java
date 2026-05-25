// agent-platform/backend/src/main/java/com/superprogrammer/agent/controller/AgentController.java
package com.superprogrammer.agent.controller;

import com.superprogrammer.agent.dto.AgentDetailVO;
import com.superprogrammer.agent.dto.AgentVO;
import com.superprogrammer.agent.dto.SkillDetailVO;
import com.superprogrammer.agent.dto.SkillVO;
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

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final MarkdownSyncService markdownSyncService;

    /**
     * 查询Agent列表（支持按分组和关键词筛选）
     */
    @GetMapping("/agents")
    @RequirePermission("agent:read")
    public ResponseEntity<R<List<AgentVO>>> listAgents(
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String keyword) {
        List<AgentVO> agents = agentService.listAgents(groupId, keyword);
        return ResponseEntity.ok(R.ok(agents));
    }

    /**
     * 获取Agent详情（含技能列表）
     */
    @GetMapping("/agents/{id}")
    @RequirePermission("agent:read")
    public ResponseEntity<R<AgentDetailVO>> getAgentDetail(@PathVariable Long id) {
        AgentDetailVO detail = agentService.getAgentDetail(id);
        return ResponseEntity.ok(R.ok(detail));
    }

    /**
     * 查询指定Agent下的技能列表
     */
    @GetMapping("/agents/{id}/skills")
    @RequirePermission("agent:read")
    public ResponseEntity<R<List<SkillVO>>> listAgentSkills(@PathVariable Long id) {
        List<SkillVO> skills = agentService.getAgentDetail(id).getSkills();
        return ResponseEntity.ok(R.ok(skills));
    }

    /**
     * 获取技能详情（含步骤）
     */
    @GetMapping("/skills/{id}")
    @RequirePermission("agent:read")
    public ResponseEntity<R<SkillDetailVO>> getSkillDetail(@PathVariable Long id) {
        SkillDetailVO detail = agentService.getSkillDetail(id);
        return ResponseEntity.ok(R.ok(detail));
    }

    /**
     * 触发Markdown同步（需admin或agent_admin权限）
     */
    @PostMapping("/agents/sync")
    @RequirePermission("agent:create")
    public ResponseEntity<R<Integer>> syncFromMarkdown() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long operatorId = (Long) authentication.getPrincipal();
        int count = markdownSyncService.syncAll(operatorId);
        return ResponseEntity.ok(R.ok("同步完成", count));
    }
}
