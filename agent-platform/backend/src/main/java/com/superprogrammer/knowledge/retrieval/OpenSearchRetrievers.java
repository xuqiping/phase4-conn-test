package com.superprogrammer.knowledge.retrieval;

import com.superprogrammer.knowledge.service.internal.RrfFusion;
import java.util.*;

public class OpenSearchRetrievers {
 private final List<Retriever> retrievers;
 private final Map<String,Double> weights;
 public OpenSearchRetrievers(List<Retriever> retrievers,Map<String,Double> weights){this.retrievers=List.copyOf(retrievers);this.weights=Map.copyOf(weights);}
 public List<RetrievalCandidate> retrieve(String query, RetrievalFilterBuilder.FilterContext filter,int limit){
  List<RrfFusion.WeightedList<String>> lists=new ArrayList<>(); Map<String,RetrievalCandidate> byId=new LinkedHashMap<>();
  for(Retriever retriever:retrievers){
   try{
    List<RetrievalCandidate> hits=retriever.retrieve(query,filter,limit);
    if(!hits.isEmpty()){
     for(RetrievalCandidate hit:hits) byId.putIfAbsent(hit.id(),hit);
     String channel=hits.get(0).channel();
     lists.add(new RrfFusion.WeightedList<>(hits.stream().map(RetrievalCandidate::id).distinct().toList(),weights.getOrDefault(channel,1d)));
    }
   }catch(RuntimeException ignored){ /* 显式单通道降级；调用方 Trace 记录 channel error */ }
  }
  if(lists.isEmpty()) return List.of();
  return RrfFusion.sortByScoreDesc(RrfFusion.fuseWeighted(lists,60)).stream().limit(limit).map(byId::get).toList();
 }
}
