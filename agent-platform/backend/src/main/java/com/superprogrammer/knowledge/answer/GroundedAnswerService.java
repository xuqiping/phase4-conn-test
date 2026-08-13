package com.superprogrammer.knowledge.answer;
import java.util.*;
public class GroundedAnswerService{
 public List<Fact> mergeBatches(List<List<Fact>> batches){LinkedHashMap<String,Fact> out=new LinkedHashMap<>();for(var batch:batches)for(var fact:batch)out.merge(fact.text(),fact,(a,b)->new Fact(a.text(),java.util.stream.Stream.concat(a.citationIds().stream(),b.citationIds().stream()).distinct().toList()));return new ArrayList<>(out.values());}
 public record Fact(String text,List<Integer> citationIds){public Fact{citationIds=List.copyOf(citationIds);}}
}
