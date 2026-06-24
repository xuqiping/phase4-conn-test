package com.superprogrammer.knowledge.service;

import com.superprogrammer.agent.service.AgentKbBindingService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.workflow.service.WorkflowKbBindingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索 scope 解析 + P4 求交（v6 §5.1）。
 *
 * <p>不变式 P4：最终范围 = 执行身份权限 ∩ 绑定范围；任一为空 → 空集（禁放大）。
 * <p>同模型约束：多 KB 合并要求 embedding_model 一致（不同模型 cosine 不可比）；
 *   混合绑定时限定首个模型组（按 kbId 升序确定）+ warn。
 *
 * <p>用基本类型入参（mode + 各绑定源），避免 knowledge → chat 实体耦合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagScopeResolver {

    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentKbBindingService agentKbBindingService;
    private final WorkflowKbBindingService workflowKbBindingService;

    /**
     * CHAT/AGENT/WORKFLOW 通用 scope 解析。
     *
     * @param mode          CHAT / AGENT / WORKFLOW
     * @param sessionKbIds  CHAT 模式会话绑定（可为 null）
     * @param agentId       AGENT 模式 agentId
     * @param workflowId    WORKFLOW 模式 workflowId
     * @param userId        执行身份（当前用户）
     * @param admin         是否 admin（owner/admin 短路全权限）
     * @return P4 求交 + 同模型过滤后的 effective kbIds；空 → 调用方跳过 RAG
     */
    public List<Long> resolveEffectiveKbs(String mode, List<Long> sessionKbIds,
                                          Long agentId, Long workflowId,
                                          Long userId, boolean admin) {
        List<Long> rawScope = rawScopeByMode(mode, sessionKbIds, agentId, workflowId);
        if (rawScope.isEmpty()) {
            return List.of();
        }
        // P4: 执行身份权限 ∩ 绑定范围
        List<Long> readable = new ArrayList<>();
        for (Long kbId : rawScope) {
            try {
                KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
                if (knowledgeBaseService.canRead(kb, userId, admin)) {
                    readable.add(kbId);
                }
            } catch (BusinessException e) {
                // KB 不存在等 → 跳过（绑定脏数据，不阻断）
                log.warn("scope 解析跳过 kbId={}: {}", kbId, e.getMessage());
            }
        }
        return enforceSameEmbeddingModel(readable);
    }

    /**
     * RETRIEVAL 节点回调专用（v6 §2.4 / §5.1）：workflow 绑定 ∩ 节点 config ∩ 用户权限。
     *
     * @param workflowId       工作流 id
     * @param nodeConfigKbIds  节点 config 携带的 kbIds（null/空 → 取工作流绑定全部）
     * @param userId           触发用户（回调请求携带）
     * @return effective kbIds
     */
    public List<Long> resolveForRetrievalNode(Long workflowId, List<Long> nodeConfigKbIds, Long userId) {
        List<Long> workflowBinding = workflowKbBindingService.listKbIds(workflowId);
        List<Long> rawScope;
        if (nodeConfigKbIds == null || nodeConfigKbIds.isEmpty()) {
            rawScope = workflowBinding;
        } else {
            // ∩ 节点配置
            rawScope = workflowBinding.stream()
                    .filter(nodeConfigKbIds::contains)
                    .toList();
        }
        if (rawScope.isEmpty()) {
            return List.of();
        }
        List<Long> readable = new ArrayList<>();
        for (Long kbId : rawScope) {
            try {
                KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
                if (knowledgeBaseService.canRead(kb, userId, false)) {
                    readable.add(kbId);
                }
            } catch (BusinessException e) {
                log.warn("retrieval 节点 scope 跳过 kbId={}: {}", kbId, e.getMessage());
            }
        }
        return enforceSameEmbeddingModel(readable);
    }

    /**
     * RETRIEVAL 节点回调（无 workflowId 可得时）：节点 config kbIds ∩ 用户权限 + 同模型。
     * 用于 callback 路径（RuntimeNodeCallbackRequest 不携带 workflowId）。
     */
    public List<Long> resolveNodeKbs(List<Long> nodeConfigKbIds, Long userId) {
        if (nodeConfigKbIds == null || nodeConfigKbIds.isEmpty()) {
            return List.of();
        }
        List<Long> readable = new ArrayList<>();
        for (Long kbId : nodeConfigKbIds) {
            try {
                KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
                if (knowledgeBaseService.canRead(kb, userId, false)) {
                    readable.add(kbId);
                }
            } catch (BusinessException e) {
                log.warn("retrieval 节点 scope 跳过 kbId={}: {}", kbId, e.getMessage());
            }
        }
        return enforceSameEmbeddingModel(readable);
    }

    private List<Long> rawScopeByMode(String mode, List<Long> sessionKbIds, Long agentId, Long workflowId) {
        if (mode == null) {
            return List.of();
        }
        return switch (mode.toUpperCase()) {
            case "AGENT" -> agentKbBindingService.listKbIds(agentId);
            case "WORKFLOW" -> workflowKbBindingService.listKbIds(workflowId);
            default -> sessionKbIds == null ? List.of() : sessionKbIds;   // CHAT（含 null/未知 → CHAT 语义）
        };
    }

    /**
     * 同 embedding_model 约束：多模型混合 → 限定首个模型组（kbId 升序确定），warn。
     * Phase1 通常全 doubao-embedding-vision，不会触发。
     */
    private List<Long> enforceSameEmbeddingModel(List<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return List.of();
        }
        Map<String, List<Long>> byModel = new LinkedHashMap<>();
        for (Long kbId : kbIds) {
            KnowledgeBase kb = knowledgeBaseService.ensure(kbId);
            byModel.computeIfAbsent(kb.getEmbeddingModel(), k -> new ArrayList<>()).add(kbId);
        }
        if (byModel.size() <= 1) {
            return kbIds;
        }
        String kept = byModel.keySet().iterator().next();
        List<Long> restricted = byModel.get(kept);
        log.warn("混合 embedding_model 绑定 {}，限定首个模型组 {} 的 kbIds={}",
                byModel.keySet(), kept, restricted);
        return restricted;
    }
}
