package com.superprogrammer.knowledge.context;
import org.junit.jupiter.api.Test; import java.util.*; import static org.junit.jupiter.api.Assertions.*;
class CoverageSelectorTest{
 @Test void budgetsTwoForExactAndUpToTwentyForListWithDocumentDiversity(){
  var s=new CoverageSelector(); assertEquals(2,s.budget("EXACT",3)); assertEquals(10,s.budget("LIST",10));
  var in=new ArrayList<CoverageSelector.Candidate>(); for(long i=1;i<=10;i++)in.add(new CoverageSelector.Candidate(i,i<=7?1L:i,1-i*.01));
  assertTrue(s.select(in,10).stream().map(CoverageSelector.Candidate::documentId).distinct().count()>=4);
 }
}
