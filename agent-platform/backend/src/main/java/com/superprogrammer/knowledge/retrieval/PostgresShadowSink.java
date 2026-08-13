package com.superprogrammer.knowledge.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresShadowSink implements ShadowRetrievalService.Sink {
    private final ShadowRetrievalMapper mapper;
    private final ObjectMapper objectMapper;
    public void save(ShadowRetrievalService.ShadowRecord value) {
        try {
            var row=new ShadowRetrievalMapper.Row(); row.id=value.id(); row.tenantId=value.tenantId(); row.kbId=value.kbId();
            row.userId=value.userId(); row.championTraceId=value.championTraceId(); row.challengerTraceId=value.challengerTraceId();
            row.championVersion=value.championVersion(); row.challengerVersion=value.challengerVersion(); row.status=value.status();
            row.rankedChunkIds=objectMapper.writeValueAsString(value.rankedChunkIds()); row.cost=value.cost();
            row.errorSummary=value.errorSummary(); row.createdAt=value.createdAt(); mapper.insert(row);
        } catch (Exception error) { throw new IllegalStateException("shadow result persistence failed",error); }
    }
}
