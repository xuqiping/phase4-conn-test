package com.superprogrammer.knowledge.citation;
import java.util.function.Predicate;
public class CitationVerifier{
 public boolean verify(Citation c,String claim,Predicate<String> support){return c!=null&&c.authorized()&&c.currentHash()&&c.locator()!=null&&!c.locator().isBlank()&&support.test(claim);}
 public record Citation(Long nodeId,String locator,String contentHash,boolean authorized,boolean currentHash){}
}
