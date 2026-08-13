package com.superprogrammer.knowledge.service;

import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.mapper.LlmUsageLogMapper;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.knowledge.entity.RagModelCall;
import com.superprogrammer.knowledge.entity.RagRankingRun;
import com.superprogrammer.knowledge.entity.RagRetrievalRun;
import com.superprogrammer.knowledge.mapper.RagModelCallMapper;
import com.superprogrammer.knowledge.mapper.RagRankingRunMapper;
import com.superprogrammer.knowledge.mapper.RagRetrievalRunMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagTraceQueryServiceTest {

    private final RagRetrievalRunMapper retrievalMapper = mock(RagRetrievalRunMapper.class);
    private final RagRankingRunMapper rankingMapper = mock(RagRankingRunMapper.class);
    private final RagModelCallMapper modelCallMapper = mock(RagModelCallMapper.class);
    private final LlmUsageLogMapper usageMapper = mock(LlmUsageLogMapper.class);
    private final AuditLogMapper auditMapper = mock(AuditLogMapper.class);
    private final RagTraceQueryService service = new RagTraceQueryService(
            retrievalMapper, rankingMapper, modelCallMapper, usageMapper, auditMapper);

    @Test
    void detail_aggregatesTimelineWithoutSensitiveBodies() {
        RagRetrievalRun retrieval = new RagRetrievalRun();
        retrieval.setId("retrieval-1"); retrieval.setTraceId("trace-1"); retrieval.setQueryHash("query-hash");
        RagRankingRun ranking = new RagRankingRun();
        ranking.setId("ranking-1"); ranking.setCandidateHash("candidate-hash");
        RagModelCall call = new RagModelCall();
        call.setId("call-1"); call.setInputHash("input-hash"); call.setOutputHash("output-hash");
        LlmUsageLogEntity usage = new LlmUsageLogEntity();
        usage.setId(8L); usage.setTraceId("trace-1"); usage.setModel("chat-model");
        AuditLogEntity audit = new AuditLogEntity();
        audit.setId(9L); audit.setTraceId("trace-1"); audit.setAction("send_message");
        audit.setDetailJson("{\"prompt\":\"must-not-return\"}");

        when(retrievalMapper.findByTraceId("trace-1")).thenReturn(List.of(retrieval));
        when(rankingMapper.findByTraceId("trace-1")).thenReturn(List.of(ranking));
        when(modelCallMapper.findByTraceId("trace-1")).thenReturn(List.of(call));
        when(usageMapper.findByTraceId("trace-1")).thenReturn(List.of(usage));
        when(auditMapper.findByTraceId("trace-1")).thenReturn(List.of(audit));

        var detail = service.detail("trace-1");

        assertEquals("trace-1", detail.getTraceId());
        assertEquals("query-hash", detail.getRetrievals().get(0).getQueryHash());
        assertEquals("candidate-hash", detail.getRankings().get(0).getCandidateHash());
        assertEquals("input-hash", detail.getModelCalls().get(0).getInputHash());
        assertEquals("send_message", detail.getAudits().get(0).getAction());
        assertFalse(detail.toString().contains("must-not-return"));
    }

    @Test
    void resolveTraceId_supportsModelUsageAndAuditReverseLookup() {
        when(modelCallMapper.findTraceIdByModelRequestId("model-1")).thenReturn("trace-model");
        when(usageMapper.findTraceIdById(8L)).thenReturn("trace-usage");
        when(auditMapper.findTraceIdById(9L)).thenReturn("trace-audit");

        assertEquals("trace-model", service.resolveTraceId("model-1", null, null));
        assertEquals("trace-usage", service.resolveTraceId(null, 8L, null));
        assertEquals("trace-audit", service.resolveTraceId(null, null, 9L));
        assertThrows(BusinessException.class, () -> service.resolveTraceId("model-1", 8L, null));
    }
}
