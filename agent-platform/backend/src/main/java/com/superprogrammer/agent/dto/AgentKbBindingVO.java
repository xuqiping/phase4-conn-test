package com.superprogrammer.agent.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Agent ↔ KB 绑定视图（阶段5 检索 scope 管理）。
 */
@Data
@Builder
public class AgentKbBindingVO {

    private Long kbId;
    private String kbName;
}
