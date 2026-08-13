package com.superprogrammer.knowledge.ranking;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.retrieval.RetrievalCandidate;
import java.util.*;

public class LlmRankingProvider implements RankingProvider {
 private final RankingLlmClient client; private final ObjectMapper mapper=new ObjectMapper();
 public LlmRankingProvider(RankingLlmClient client){this.client=client;}
 @Override public List<RankingResult> rank(String query,List<RetrievalCandidate> candidates,String model){
  if(model==null||model.isBlank()) throw new IllegalStateException("LLM ranking 未选择可用模型");
  String json=client.rank(query,candidates,model);
  try{
   List<Map<String,Object>> rows=mapper.readValue(json,new TypeReference<>(){}); Set<String> allowed=new HashSet<>(); candidates.forEach(c->allowed.add(c.id())); Set<String> seen=new HashSet<>(); List<RankingResult> out=new ArrayList<>();
   for(Map<String,Object> row:rows){String id=String.valueOf(row.get("id")); if(!allowed.contains(id)||!seen.add(id)) throw new IllegalArgumentException("ranking candidate id invalid: "+id); Object s=row.get("score"); if(!(s instanceof Number n)) throw new IllegalArgumentException("ranking score invalid"); out.add(new RankingResult(id,n.doubleValue(),"LLM",model));}
   out.sort(Comparator.comparingDouble(RankingResult::score).reversed()); return out;
  }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("ranking JSON invalid",e);}
 }
 @Override public String mode(){return "LLM";}
 @FunctionalInterface public interface RankingLlmClient{String rank(String query,List<RetrievalCandidate> candidates,String model);}
}
