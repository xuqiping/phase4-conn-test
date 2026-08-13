package com.superprogrammer.knowledge.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.opensearch.IndexAliasService;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.opensearch.client.Request;
import org.opensearch.client.RestClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpenSearchProductionRetrievalGateway implements ProductionRetrievalGateway {
    private final ObjectProvider<RestClient> clientProvider;
    private final ObjectMapper mapper;

    public OpenSearchProductionRetrievalGateway(ObjectProvider<RestClient> clientProvider, ObjectMapper mapper) {
        this.clientProvider = clientProvider;
        this.mapper = mapper;
    }

    @Override
    public List<RetrievalCandidate> retrieve(String query, RetrievalFilterBuilder.FilterContext filter,
                                             List<String> strategies, int limit) {
        RestClient client = clientProvider.getIfAvailable();
        if (client == null || strategies == null || strategies.stream().noneMatch(s -> s.equals("EXACT") || s.equals("SPARSE"))) {
            return List.of();
        }
        try {
            JsonNode boolFilter = mapper.readTree(filter.json()).path("bool");
            List<Map<String, Object>> should = new ArrayList<>();
            if (strategies.contains("EXACT")) should.add(Map.of("match_phrase", Map.of("sparseText", query)));
            if (strategies.contains("SPARSE")) should.add(Map.of("match", Map.of("sparseText", Map.of("query", query))));
            Map<String, Object> bool = new LinkedHashMap<>();
            bool.put("filter", mapper.convertValue(boolFilter.path("filter"), List.class));
            bool.put("should", should);
            bool.put("minimum_should_match", 1);
            String body = mapper.writeValueAsString(Map.of("size", limit, "query", Map.of("bool", bool),
                    "_source", List.of("nodeId", "documentId", "sparseText", "contentHash")));
            Request request = new Request("POST", "/" + IndexAliasService.readAlias(extractKb(filter.summary())) + "/_search");
            request.setEntity(new NStringEntity(body, ContentType.APPLICATION_JSON));
            JsonNode hits = mapper.readTree(client.performRequest(request).getEntity().getContent()).path("hits").path("hits");
            List<RetrievalCandidate> out = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                out.add(new RetrievalCandidate(hit.path("_id").asText(), source.path("nodeId").asLong(),
                        source.path("documentId").asLong(), "OPENSEARCH", hit.path("_score").asDouble(),
                        null, source.path("sparseText").asText(), source.path("contentHash").asText()));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private long extractKb(String summary) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|,)kb=(\\d+)").matcher(summary);
        if (!matcher.find()) throw new IllegalArgumentException("filter summary missing kb");
        return Long.parseLong(matcher.group(1));
    }
}
