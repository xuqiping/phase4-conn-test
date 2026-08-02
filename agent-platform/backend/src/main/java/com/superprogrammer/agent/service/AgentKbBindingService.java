package com.superprogrammer.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superprogrammer.agent.dto.AgentKbBindingVO;
import com.superprogrammer.agent.entity.Agent;
import com.superprogrammer.agent.entity.AgentKbBinding;
import com.superprogrammer.agent.mapper.AgentKbBindingMapper;
import com.superprogrammer.agent.mapper.AgentMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent ↔ KB 检索范围绑定（V25）。
 * 绑定 = 检索 scope；P4 求交在 RagScopeResolver（执行身份权限 ∩ 此绑定）。
 * 管理 assertManage：owner||admin（复用 AgentPermissionService.canManage 语义）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentKbBindingService {

    private final AgentKbBindingMapper bindingMapper;
    private final AgentPermissionService agentPermissionService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final AgentMapper agentMapper;
    private final ObjectMapper objectMapper;

    /** 检索 scope 用：返回该 Agent 绑定的 KB id（active，无鉴权，供 RagScopeResolver）。 */
    public List<Long> listKbIds(Long agentId) {
        if (agentId == null) {
            return List.of();
        }
        LambdaQueryWrapper<AgentKbBinding> w = new LambdaQueryWrapper<>();
        w.eq(AgentKbBinding::getAgentId, agentId)
                .eq(AgentKbBinding::getDeleted, 0);
        return bindingMapper.selectList(w).stream()
                .map(AgentKbBinding::getKbId)
                .distinct()
                .toList();
    }

    /** 管理：列出绑定（含 KB 名），assertManage。 */
    public List<AgentKbBindingVO> listBindings(Long agentId, Long operatorId, boolean admin) {
        assertManage(agentId, operatorId, admin);
        return listKbIds(agentId).stream()
                .map(kbId -> AgentKbBindingVO.builder()
                        .kbId(kbId)
                        .kbName(kbNameOf(kbId))
                        .build())
                .toList();
    }

    /** 管理：全量替换绑定，assertManage。 */
    @Transactional
    public void saveBindings(Long agentId, List<Long> kbIds, Long operatorId, boolean admin) {
        assertManage(agentId, operatorId, admin);
        // 1. 逻辑删现有
        LambdaQueryWrapper<AgentKbBinding> w = new LambdaQueryWrapper<>();
        w.eq(AgentKbBinding::getAgentId, agentId).eq(AgentKbBinding::getDeleted, 0);
        bindingMapper.delete(w);   // @TableLogic 逻辑删
        // 2. 插入新（去重 + 校 KB 存在）
        if (kbIds == null || kbIds.isEmpty()) {
            return;
        }
        List<Long> distinct = kbIds.stream().distinct().toList();
        for (Long kbId : distinct) {
            if (knowledgeBaseMapper.selectById(kbId) == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库不存在: " + kbId);
            }
            AgentKbBinding b = new AgentKbBinding();
            b.setAgentId(agentId);
            b.setKbId(kbId);
            b.setTenantId(1L);
            b.setGrantedBy(operatorId);
            b.setCreatedBy(operatorId);
            bindingMapper.insert(b);
        }
        log.info("Agent KB 绑定更新 agentId={} kbIds={}", agentId, distinct);
    }

    /** 设置 Agent 级记忆模式开关（写 Agent.config JSONB `ragEnabled`，V26）。null 表继承。 */
    @Transactional
    public void setRagEnabled(Long agentId, Boolean enabled, Long operatorId, boolean admin) {
        assertManage(agentId, operatorId, admin);
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
        try {
            ObjectNode node = (agent.getConfig() == null || agent.getConfig().isBlank())
                    ? objectMapper.createObjectNode()
                    : (ObjectNode) objectMapper.readTree(agent.getConfig());
            if (enabled == null) {
                node.remove("ragEnabled");
            } else {
                node.put("ragEnabled", enabled);
            }
            agent.setConfig(objectMapper.writeValueAsString(node));
            agent.setUpdatedBy(operatorId);
            agentMapper.updateById(agent);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Agent.config 写 ragEnabled 失败: " + e.getMessage());
        }
    }

    private void assertManage(Long agentId, Long operatorId, boolean admin) {
        if (!agentPermissionService.canManage(agentId, operatorId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或 Agent 创建者可管理 KB 绑定");
        }
    }

    private String kbNameOf(Long kbId) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        return kb == null ? null : kb.getName();
    }
}
