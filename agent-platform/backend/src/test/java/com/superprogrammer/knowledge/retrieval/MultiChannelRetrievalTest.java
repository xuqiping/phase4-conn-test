package com.superprogrammer.knowledge.retrieval;
import com.superprogrammer.knowledge.service.internal.RrfFusion;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MultiChannelRetrievalTest {
 @Test void fusesChannelsByStableIdAndKeepsWorkingWhenOneFails(){
  Retriever ok=(q,f,l)->List.of(new RetrievalCandidate("1",1L,1L,"SPARSE",9),new RetrievalCandidate("2",2L,1L,"SPARSE",8));
  Retriever dense=(q,f,l)->List.of(new RetrievalCandidate("2",2L,1L,"DENSE",.9),new RetrievalCandidate("1",1L,1L,"DENSE",.8));
  Retriever bad=(q,f,l)->{throw new RuntimeException("down");};
  var out=new OpenSearchRetrievers(List.of(ok,dense,bad),Map.of("SPARSE",1d,"DENSE",2d)).retrieve("q",new RetrievalFilterBuilder.FilterContext("{}","safe"),10);
  assertEquals(List.of("2","1"),out.stream().map(RetrievalCandidate::id).toList());
 }
}
