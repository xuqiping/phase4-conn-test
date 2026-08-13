package com.superprogrammer.knowledge.retrieval;
import java.util.*;
public class RetrievalRouter{
 private final OpenSearchRetrievers retrievers;
 public RetrievalRouter(OpenSearchRetrievers retrievers){this.retrievers=retrievers;}
 public List<RetrievalCandidate> supplement(List<String> missing,RetrievalFilterBuilder.FilterContext inherited,int limit){
  if(inherited==null)throw new IllegalArgumentException("supplement retrieval must inherit FilterContext");
  LinkedHashMap<String,RetrievalCandidate> merged=new LinkedHashMap<>(); for(String q:missing)for(var c:retrievers.retrieve(q,inherited,limit))merged.putIfAbsent(c.id(),c);return new ArrayList<>(merged.values());
 }
}
