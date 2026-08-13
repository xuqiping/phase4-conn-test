package com.superprogrammer.knowledge.opensearch;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.update_aliases.Action;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class IndexAliasService {

    private final OpenSearchClient client;

    public IndexAliasService() {
        this.client = null;
    }

    public IndexAliasService(OpenSearchClient client) {
        this.client = client;
    }

    public List<AliasAction> switchPlan(long knowledgeBaseId, String fromIndex, String toIndex) {
        String read = readAlias(knowledgeBaseId);
        String write = writeAlias(knowledgeBaseId);
        return List.of(
                new AliasAction("REMOVE", fromIndex, read, false),
                new AliasAction("REMOVE", fromIndex, write, false),
                new AliasAction("ADD", toIndex, read, false),
                new AliasAction("ADD", toIndex, write, true));
    }

    public List<AliasAction> activatePlan(long knowledgeBaseId, String toIndex) {
        return List.of(
                new AliasAction("ADD", toIndex, readAlias(knowledgeBaseId), false),
                new AliasAction("ADD", toIndex, writeAlias(knowledgeBaseId), true));
    }

    public List<AliasAction> rollbackPlan(long knowledgeBaseId, String currentIndex, String previousIndex) {
        return switchPlan(knowledgeBaseId, currentIndex, previousIndex);
    }

    public void execute(List<AliasAction> plan) throws IOException {
        if (client == null) throw new IllegalStateException("OpenSearch client is disabled");
        client.indices().updateAliases(request -> request.actions(plan.stream().map(this::toClientAction).toList()));
    }

    private Action toClientAction(AliasAction action) {
        if ("ADD".equals(action.operation())) {
            return new Action.Builder().add(add -> add.index(action.index()).alias(action.alias())
                    .isWriteIndex(action.writeIndex())).build();
        }
        return new Action.Builder().remove(remove -> remove.index(action.index()).alias(action.alias())).build();
    }

    public static String readAlias(long knowledgeBaseId) { return "kb-" + knowledgeBaseId + "-chunks-read"; }
    public static String writeAlias(long knowledgeBaseId) { return "kb-" + knowledgeBaseId + "-chunks-write"; }

    public record AliasAction(String operation, String index, String alias, boolean writeIndex) {}
}
