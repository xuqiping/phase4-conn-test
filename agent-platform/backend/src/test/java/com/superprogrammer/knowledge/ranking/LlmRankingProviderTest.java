package com.superprogrammer.knowledge.ranking;
import com.superprogrammer.knowledge.retrieval.RetrievalCandidate;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class LlmRankingProviderTest {
 @Test void acceptsOnlyWhitelistedUniqueIds(){
  var c=List.of(new RetrievalCandidate("1",1L,1L,"DENSE",1,"标题1","正文1"),new RetrievalCandidate("2",2L,1L,"DENSE",.5,"标题2","正文2"));
  var ok=new LlmRankingProvider((q,x,m)->"[{\"id\":\"2\",\"score\":0.9},{\"id\":\"1\",\"score\":0.4}]");
  assertEquals(List.of("2","1"),ok.rank("q",c,"chosen-model").stream().map(RankingResult::candidateId).toList());
  var bad=new LlmRankingProvider((q,x,m)->"[{\"id\":\"999\",\"score\":1}]");
  assertThrows(IllegalArgumentException.class,()->bad.rank("q",c,"chosen-model"));
 }
}
