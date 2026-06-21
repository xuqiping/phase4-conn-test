package com.superprogrammer.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.mapper.KnowledgeBaseMapper;
import com.superprogrammer.workflow.dto.WorkflowKbBindingVO;
import com.superprogrammer.workflow.entity.Workflow;
import com.superprogrammer.workflow.entity.WorkflowKbBinding;
import com.superprogrammer.workflow.mapper.WorkflowKbBindingMapper;
import com.superprogrammer.workflow.mapper.WorkflowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Workflow ↔ KB 检索范围绑定（V25，与 AgentKbBindingService 对称）。
 * canManage = owner||admin（工作流级，inline）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowKbBindingService {

    private final WorkflowKbBindingMapper bindingMapper;
    private final WorkflowMapper workflowMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    /** 检索 scope 用（供 RagScopeResolver + RETRIEVAL 节点回调）。 */
    public List<Long> listKbIds(Long workflowId) {
        if (workflowId == null) {
            return List.of();
        }
        LambdaQueryWrapper<WorkflowKbBinding> w = new LambdaQueryWrapper<>();
        w.eq(WorkflowKbBinding::getWorkflowId, workflowId)
                .eq(WorkflowKbBinding::getDeleted, 0);
        return bindingMapper.selectList(w).stream()
                .map(WorkflowKbBinding::getKbId)
                .distinct()
                .toList();
    }

    public List<WorkflowKbBindingVO> listBindings(Long workflowId, Long operatorId, boolean admin) {
        assertManage(workflowId, operatorId, admin);
        return listKbIds(workflowId).stream()
                .map(kbId -> WorkflowKbBindingVO.builder()
                        .kbId(kbId)
                        .kbName(kbNameOf(kbId))
                        .build())
                .toList();
    }

    @Transactional
    public void saveBindings(Long workflowId, List<Long> kbIds, Long operatorId, boolean admin) {
        assertManage(workflowId, operatorId, admin);
        LambdaQueryWrapper<WorkflowKbBinding> w = new LambdaQueryWrapper<>();
        w.eq(WorkflowKbBinding::getWorkflowId, workflowId).eq(WorkflowKbBinding::getDeleted, 0);
        bindingMapper.delete(w);
        if (kbIds == null || kbIds.isEmpty()) {
            return;
        }
        List<Long> distinct = kbIds.stream().distinct().toList();
        for (Long kbId : distinct) {
            if (knowledgeBaseMapper.selectById(kbId) == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库不存在: " + kbId);
            }
            WorkflowKbBinding b = new WorkflowKbBinding();
            b.setWorkflowId(workflowId);
            b.setKbId(kbId);
            b.setTenantId(1L);
            b.setGrantedBy(operatorId);
            b.setCreatedBy(operatorId);
            bindingMapper.insert(b);
        }
        log.info("Workflow KB 绑定更新 workflowId={} kbIds={}", workflowId, distinct);
    }

    /** 设置 Workflow 级记忆模式开关（写 workflow.rag_enabled，V26）。null 表继承。 */
    @Transactional
    public void setRagEnabled(Long workflowId, Boolean enabled, Long operatorId, boolean admin) {
        Workflow wf = workflowMapper.selectById(workflowId);
        if (wf == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流不存在");
        }
        if (!admin && (operatorId == null || !operatorId.equals(wf.getOwnerId()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或工作流创建者可管理记忆模式开关");
        }
        wf.setRagEnabled(enabled);
        wf.setUpdatedBy(operatorId);
        workflowMapper.updateById(wf);
        log.info("Workflow 记忆模式开关更新 workflowId={} ragEnabled={}", workflowId, enabled);
    }

    private void assertManage(Long workflowId, Long operatorId, boolean admin) {
        Workflow wf = workflowMapper.selectById(workflowId);
        if (wf == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流不存在");
        }
        if (!admin && (operatorId == null || !operatorId.equals(wf.getOwnerId()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或工作流创建者可管理 KB 绑定");
        }
    }

    private String kbNameOf(Long kbId) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        return kb == null ? null : kb.getName();
    }
}
