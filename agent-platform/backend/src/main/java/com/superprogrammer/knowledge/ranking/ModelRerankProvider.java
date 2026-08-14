package com.superprogrammer.knowledge.ranking;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.RerankRequest;
import com.superprogrammer.knowledge.retrieval.RetrievalCandidate;
import java.util.List;
public class ModelRerankProvider implements RankingProvider{
 private final boolean available; private final RankingProvider delegate; private final LlmGateway gateway;
 public ModelRerankProvider(boolean available,RankingProvider delegate){this.available=available;this.delegate=delegate;this.gateway=null;}
 public ModelRerankProvider(LlmGateway gateway){this.available=gateway!=null;this.delegate=null;this.gateway=gateway;}
 public List<RankingResult> rank(String q,List<RetrievalCandidate> c,String model){
  if(!available)throw new IllegalStateException("RERANK 模式未配置可用专用模型");
  if(gateway==null){if(delegate==null)throw new IllegalStateException("RERANK 模式未配置可用专用模型");return delegate.rank(q,c,model);}
  if(c==null||c.isEmpty())return List.of();
  var documents=c.stream().map(ModelRerankProvider::candidateText).toList();
  var result=gateway.rerank(RerankRequest.builder().model(model).query(q).documents(documents).build());
  return result.getItems().stream().map(item->{
   int index=item.getIndex();
   if(index<0||index>=c.size())throw new IllegalStateException("RERANK 返回候选索引越界");
   return new RankingResult(c.get(index).id(),item.getScore(),"RERANK",model);
  }).toList();
 }
 public String mode(){return "RERANK";}
 public boolean available(){return available&&(gateway!=null||delegate!=null);}
 private static String candidateText(RetrievalCandidate candidate){
  if(candidate.content()!=null&&!candidate.content().isBlank())return candidate.content();
  if(candidate.title()!=null&&!candidate.title().isBlank())return candidate.title();
  return candidate.id();
 }
}
