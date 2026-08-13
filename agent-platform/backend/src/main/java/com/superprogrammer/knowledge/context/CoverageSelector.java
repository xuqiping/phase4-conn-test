package com.superprogrammer.knowledge.context;
import java.util.*;
public class CoverageSelector{
 public int budget(String type,int requested){int base=switch(type){case "EXACT"->2;case "PROCEDURE"->10;case "COMPARISON","LIST"->20;default->5;};return Math.max(2,Math.min(20,Math.min(base,Math.max(2,requested))));}
 public List<Candidate> select(List<Candidate> input,int budget){
  List<Candidate> sorted=input.stream().sorted(Comparator.comparingDouble(Candidate::score).reversed()).toList(); List<Candidate> out=new ArrayList<>(); Map<Long,Integer> perDoc=new HashMap<>(); int cap=Math.max(1,(int)Math.ceil(budget*.5));
  for(Candidate c:sorted)if(perDoc.getOrDefault(c.documentId(),0)<cap){out.add(c);perDoc.merge(c.documentId(),1,Integer::sum);if(out.size()==budget)break;}
  if(out.size()<budget)for(Candidate c:sorted)if(!out.contains(c)){out.add(c);if(out.size()==budget)break;}
  return out;
 }
 public record Candidate(Long nodeId,Long documentId,double score){}
}
