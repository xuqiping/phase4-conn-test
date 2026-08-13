package com.superprogrammer.knowledge.opensearch;

import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.opensearch.client.Request;
import org.opensearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;

@Service
public class OpenSearchReconciliationService {

    private final RestClient client;

    public OpenSearchReconciliationService() { this.client = null; }
    public OpenSearchReconciliationService(RestClient client) { this.client = client; }

    public Report compare(List<NodeSnapshot> pg, List<NodeSnapshot> openSearch) {
        Map<Long, NodeSnapshot> left = byId(pg);
        Map<Long, NodeSnapshot> right = byId(openSearch);
        List<Long> missing = sortedDifference(left.keySet(), right.keySet());
        List<Long> orphan = sortedDifference(right.keySet(), left.keySet());
        List<Long> hash = new ArrayList<>();
        List<Long> acl = new ArrayList<>();
        for (Long id : left.keySet()) {
            if (!right.containsKey(id)) continue;
            if (!Objects.equals(left.get(id).contentHash(), right.get(id).contentHash())) hash.add(id);
            if (!new HashSet<>(left.get(id).aclTokens()).equals(new HashSet<>(right.get(id).aclTokens()))) acl.add(id);
        }
        Collections.sort(hash);
        Collections.sort(acl);
        return new Report(missing, orphan, hash, acl);
    }

    public RepairPlan plan(Report report, boolean dryRun) {
        List<Long> reindex = Stream.of(report.missingIds(), report.hashDriftIds(), report.aclDriftIds())
                .flatMap(Collection::stream).distinct().sorted().toList();
        return new RepairPlan(dryRun, reindex, report.orphanIds());
    }

    public void deleteKnowledgeBase(long knowledgeBaseId) throws IOException {
        if (client == null) return;
        String body = "{\"query\":{\"term\":{\"knowledgeBaseId\":" + knowledgeBaseId + "}}}";
        Request request = new Request("POST", "/" + IndexAliasService.writeAlias(knowledgeBaseId) + "/_delete_by_query");
        request.setEntity(new NStringEntity(body, ContentType.APPLICATION_JSON));
        client.performRequest(request);
    }

    private static Map<Long, NodeSnapshot> byId(List<NodeSnapshot> values) {
        Map<Long, NodeSnapshot> result = new HashMap<>();
        for (NodeSnapshot value : values == null ? List.<NodeSnapshot>of() : values) result.put(value.nodeId(), value);
        return result;
    }

    private static List<Long> sortedDifference(Set<Long> left, Set<Long> right) {
        return left.stream().filter(id -> !right.contains(id)).sorted().toList();
    }

    public record NodeSnapshot(Long nodeId, String contentHash, List<String> aclTokens) {
        public NodeSnapshot { aclTokens = aclTokens == null ? List.of() : List.copyOf(aclTokens); }
    }
    public record Report(List<Long> missingIds, List<Long> orphanIds,
                         List<Long> hashDriftIds, List<Long> aclDriftIds) {}
    public record RepairPlan(boolean dryRun, List<Long> reindexIds, List<Long> deleteIds) {}
}
