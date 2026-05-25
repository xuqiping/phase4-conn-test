// agent-platform/backend/src/main/java/com/superprogrammer/agent/controller/AgentGroupController.java
package com.superprogrammer.agent.controller;

import com.superprogrammer.agent.dto.AgentGroupVO;
import com.superprogrammer.agent.service.AgentService;
import com.superprogrammer.auth.security.RequirePermission;
import com.superprogrammer.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent-groups")
@RequiredArgsConstructor
public class AgentGroupController {

    private final AgentService agentService;

    @GetMapping
    @RequirePermission("agent:read")
    public ResponseEntity<R<List<AgentGroupVO>>> listGroups() {
        List<AgentGroupVO> groups = agentService.listGroups();
        return ResponseEntity.ok(R.ok(groups));
    }
}
