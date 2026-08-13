package com.superprogrammer.knowledge.ranking;
import com.superprogrammer.knowledge.retrieval.RetrievalCandidate;
import java.util.*;
public class RankingEngine{
 private final Map<String,RankingProvider> providers;
 public RankingEngine(List<RankingProvider> providers){Map<String,RankingProvider> m=new HashMap<>();providers.forEach(p->m.put(p.mode(),p));this.providers=Map.copyOf(m);}
 public List<RankingResult> rank(String mode,String query,List<RetrievalCandidate> candidates,String model){RankingProvider p=providers.get(mode);if(p==null)throw new IllegalStateException("ranking mode unavailable: "+mode);return p.rank(query,candidates,model);}
}
