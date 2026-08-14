package com.superprogrammer.knowledge.ranking;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.RerankResult;
import com.superprogrammer.knowledge.retrieval.RetrievalCandidate;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
class ModelRerankProviderTest {
 @Test void unavailableCapabilityFailsClosed(){var p=new ModelRerankProvider(false,null);assertThrows(IllegalStateException.class,()->p.rank("q",List.of(),"rerank-model"));}

 @Test void gatewayIndexesMapBackToCandidates(){
  LlmGateway gateway=mock(LlmGateway.class);
  var candidates=List.of(
    new RetrievalCandidate("c1",1L,1L,"DENSE",0.3,"t1","doc one"),
    new RetrievalCandidate("c2",2L,2L,"DENSE",0.2,"t2","doc two"),
    new RetrievalCandidate("c3",3L,3L,"DENSE",0.1,"t3","doc three"));
  when(gateway.rerank(any())).thenReturn(RerankResult.builder().model("rerank-model").items(List.of(
    RerankResult.Item.builder().index(2).score(0.95).build(),
    RerankResult.Item.builder().index(0).score(0.80).build(),
    RerankResult.Item.builder().index(1).score(0.70).build())).build());

  var result=new ModelRerankProvider(gateway).rank("query",candidates,"rerank-model");

  assertEquals(List.of("c3","c1","c2"),result.stream().map(RankingResult::candidateId).toList());
  assertEquals("RERANK",result.get(0).provider());
  verify(gateway).rerank(argThat(req -> req.getModel().equals("rerank-model")
    && req.getQuery().equals("query") && req.getDocuments().equals(List.of("doc one","doc two","doc three"))));
 }
}
