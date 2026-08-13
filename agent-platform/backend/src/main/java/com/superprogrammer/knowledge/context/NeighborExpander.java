package com.superprogrammer.knowledge.context;
import java.util.*;
public class NeighborExpander{
 public List<Long> expand(List<Long> selected,Map<Long,List<Long>> neighbors,Set<Long> authorized){LinkedHashSet<Long> out=new LinkedHashSet<>(selected);for(Long id:selected)for(Long n:neighbors.getOrDefault(id,List.of()))if(authorized.contains(n))out.add(n);return new ArrayList<>(out);}
}
