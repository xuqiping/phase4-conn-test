package com.superprogrammer.knowledge.ranking;
import com.superprogrammer.knowledge.retrieval.RetrievalCandidate;
import java.util.List;
public class DisabledRankingProvider implements RankingProvider{
 public List<RankingResult> rank(String q,List<RetrievalCandidate> c,String m){return c.stream().map(x->new RankingResult(x.id(),x.rawScore(),"DISABLED",null)).toList();}
 public String mode(){return "DISABLED";}
}
