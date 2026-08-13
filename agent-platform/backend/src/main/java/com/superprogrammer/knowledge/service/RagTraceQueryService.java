package com.superprogrammer.knowledge.service;

import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.mapper.LlmUsageLogMapper;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.knowledge.dto.RagTraceDetailVO;
import com.superprogrammer.knowledge.mapper.RagModelCallMapper;
import com.superprogrammer.knowledge.mapper.RagRankingRunMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RagTraceQueryService {
    private final RagRetrievalRunMapper retrievalMapper;
    private final RagRankingRunMapper rankingMapper;
    private final RagModelCallMapper modelCallMapper;
    private final LlmUsageLogMapper usageMapper;
    private final AuditLogMapper auditMapper;

    public RagTraceDetailVO detail(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "traceId 不能为空");
        }
        RagTraceDetailVO vo = new RagTraceDetailVO();
        vo.setTraceId(traceId);
        vo.setRetrievals(retrievalMapper.findByTraceId(traceId));
        vo.setRankings(rankingMapper.findByTraceId(traceId));
        vo.setModelCalls(modelCallMapper.findByTraceId(traceId));
        vo.setUsages(usageMapper.findByTraceId(traceId).stream().map(RagTraceQueryService::toUsage).toList());
        vo.setAudits(auditMapper.findByTraceId(traceId).stream().map(RagTraceQueryService::toAudit).toList());
        return vo;
    }

    public String resolveTraceId(String modelRequestId, Long usageLogId, Long auditLogId) {
        int supplied = (hasText(modelRequestId) ? 1 : 0) + (usageLogId != null ? 1 : 0) + (auditLogId != null ? 1 : 0);
        if (supplied != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "必须且只能提供一个反查条件");
        }
        String traceId = hasText(modelRequestId) ? modelCallMapper.findTraceIdByModelRequestId(modelRequestId)
                : usageLogId != null ? usageMapper.findTraceIdById(usageLogId)
                : auditMapper.findTraceIdById(auditLogId);
        if (!hasText(traceId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到关联的 RAG Trace");
        }
        return traceId;
    }

    private static RagTraceDetailVO.UsageItem toUsage(LlmUsageLogEntity e) {
        RagTraceDetailVO.UsageItem item = new RagTraceDetailVO.UsageItem();
        item.setId(e.getId()); item.setCreatedAt(e.getCreatedAt()); item.setUserId(e.getUserId());
        item.setModel(e.getModel()); item.setKind(e.getKind()); item.setTokensInput(e.getTokensInput());
        item.setTokensOutput(e.getTokensOutput()); item.setCostYuan(e.getCostYuan());
        item.setPointsConsumed(e.getPointsConsumed()); item.setStatus(e.getStatus());
        return item;
    }

    private static RagTraceDetailVO.AuditItem toAudit(AuditLogEntity e) {
        RagTraceDetailVO.AuditItem item = new RagTraceDetailVO.AuditItem();
        item.setId(e.getId()); item.setCreatedAt(e.getCreatedAt()); item.setUserId(e.getUserId());
        item.setUsername(e.getUsername()); item.setModule(e.getModule()); item.setAction(e.getAction());
        item.setTargetType(e.getTargetType()); item.setTargetId(e.getTargetId()); item.setResult(e.getResult());
        return item;
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
}
