package com.superprogrammer.knowledge.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ShadowComparisonQueryService {
    private final ShadowRetrievalMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ShadowComparison> findRecent(long tenantId, long kbId, String status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return mapper.findRecent(tenantId, kbId, normalizedStatus, safeLimit).stream().map(this::toView).toList();
    }

    private ShadowComparison toView(ShadowRetrievalMapper.Row row) {
        List<String> ranked;
        try { ranked = objectMapper.readValue(row.rankedChunkIds, new TypeReference<>() {}); }
        catch (Exception ignored) { ranked = List.of(); }
        return new ShadowComparison(row.id, row.kbId, row.championTraceId, row.challengerTraceId,
                row.championVersion, row.challengerVersion, row.status, ranked,
                row.cost == null ? 0d : row.cost, row.errorSummary, row.createdAt);
    }

    public record ShadowComparison(Long id, Long kbId, String championTraceId, String challengerTraceId,
                                   String championVersion, String challengerVersion, String status,
                                   List<String> rankedChunkIds, double cost, String errorSummary,
                                   java.time.OffsetDateTime createdAt) {}
}
