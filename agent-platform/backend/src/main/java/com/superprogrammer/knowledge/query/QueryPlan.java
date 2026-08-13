package com.superprogrammer.knowledge.query;

import java.util.List;
import java.util.Map;

public record QueryPlan(String queryType, String answerShape, Map<String, String> filters,
                        List<String> strategies, boolean exhaustive, boolean multiHop,
                        boolean requiresLlmAnalysis) {
    public QueryPlan {
        filters = Map.copyOf(filters);
        strategies = List.copyOf(strategies);
    }
}
