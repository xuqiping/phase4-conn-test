package com.superprogrammer.knowledge.ranking;
import com.superprogrammer.knowledge.retrieval.RetrievalCandidate;
import java.util.List;
public class ModelRerankProvider implements RankingProvider{
 private final boolean available; private final RankingProvider delegate;
 public ModelRerankProvider(boolean available,RankingProvider delegate){this.available=available;this.delegate=delegate;}
 public List<RankingResult> rank(String q,List<RetrievalCandidate> c,String model){if(!available||delegate==null)throw new IllegalStateException("RERANK 模式未配置可用专用模型");return delegate.rank(q,c,model);}
 public String mode(){return "RERANK";}
 public boolean available(){return available&&delegate!=null;}
}
