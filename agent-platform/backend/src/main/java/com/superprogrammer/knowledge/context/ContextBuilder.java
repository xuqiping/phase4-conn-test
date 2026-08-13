package com.superprogrammer.knowledge.context;
import com.superprogrammer.knowledge.util.TokenEstimator; import java.util.*;
public class ContextBuilder{
 public List<Item> build(List<Item> input,int tokenCap){List<Item> out=new ArrayList<>();Set<String> seen=new HashSet<>();int used=0;for(Item i:input){if(!i.authorized()||!i.currentHash()||!seen.add(i.contentHash()))continue;int t=Math.max(1,TokenEstimator.estimate(i.content()));if(used+t>tokenCap)continue;out.add(i);used+=t;}return out;}
 public record Item(Long nodeId,String content,String contentHash,boolean authorized,boolean currentHash){}
}
