package com.superprogrammer.knowledge.retrieval;
import java.util.List;
@FunctionalInterface
public interface Retriever {
 List<RetrievalCandidate> retrieve(String query, RetrievalFilterBuilder.FilterContext filter, int limit);
}
