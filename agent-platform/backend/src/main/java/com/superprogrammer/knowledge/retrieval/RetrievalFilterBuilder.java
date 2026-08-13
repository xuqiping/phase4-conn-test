package com.superprogrammer.knowledge.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RetrievalFilterBuilder {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FilterContext build(Long tenantId, Long knowledgeBaseId, List<String> aclTokens, String version) {
        if (tenantId == null || knowledgeBaseId == null) throw new IllegalArgumentException("tenantId and knowledgeBaseId are required");
        List<String> acl = aclTokens == null ? List.of() : aclTokens.stream().filter(v -> v != null && !v.isBlank()).distinct().toList();
        Map<String,Object> bool = new LinkedHashMap<>();
        java.util.ArrayList<Map<String, ?>> filters = new java.util.ArrayList<>(List.of(
                Map.of("term", Map.of("tenantId", tenantId)),
                Map.of("term", Map.of("knowledgeBaseId", knowledgeBaseId)),
                Map.of("term", Map.of("status", "ACTIVE")),
                Map.of("terms", Map.of("aclTokens", acl))));
        if (version != null && !version.isBlank()) {
            try { filters.add(Map.of("term", Map.of("documentVersionId", Long.parseLong(version)))); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("version must resolve to documentVersionId"); }
        }
        bool.put("filter", filters);
        String summary = "tenant=1,kb=" + knowledgeBaseId + ",acl=" + acl.size()
                + ",status=ACTIVE" + (version == null ? "" : ",version=" + version);
        try { return new FilterContext(objectMapper.writeValueAsString(Map.of("bool", bool)), summary); }
        catch (JsonProcessingException e) { throw new IllegalStateException("filter serialization failed", e); }
    }

    public record FilterContext(String json, String summary) {}
}
