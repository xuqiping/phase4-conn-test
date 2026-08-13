package com.superprogrammer.knowledge.evaluation;
import java.util.*;
public class RagMetricsCalculator{
 public Metrics calculate(List<String> ranked,Set<String> relevant,int k){if(relevant.isEmpty())return new Metrics(1,0,0);int hit=0;double rr=0,dcg=0;for(int i=0;i<Math.min(k,ranked.size());i++)if(relevant.contains(ranked.get(i))){hit++;if(rr==0)rr=1d/(i+1);dcg+=1d/(Math.log(i+2)/Math.log(2));}double idcg=0;for(int i=0;i<Math.min(k,relevant.size());i++)idcg+=1d/(Math.log(i+2)/Math.log(2));return new Metrics((double)hit/relevant.size(),rr,idcg==0?0:dcg/idcg);}
 public record Metrics(double recall,double mrr,double ndcg){}
}
