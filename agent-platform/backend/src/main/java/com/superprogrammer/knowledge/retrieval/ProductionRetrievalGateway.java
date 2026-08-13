package com.superprogrammer.knowledge.retrieval;

import java.util.List;

/** 生产多通道召回入口；实现必须接收不可省略的强制 FilterContext。 */
public interface ProductionRetrievalGateway {
    List<RetrievalCandidate> retrieve(String query, RetrievalFilterBuilder.FilterContext filter,
                                      List<String> strategies, int limit);
}
