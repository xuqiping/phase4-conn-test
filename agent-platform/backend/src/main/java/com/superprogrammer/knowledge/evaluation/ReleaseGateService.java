package com.superprogrammer.knowledge.evaluation;
import java.util.*;
public class ReleaseGateService{
 private final Map<String,Double> thresholds; public ReleaseGateService(Map<String,Double> thresholds){this.thresholds=Map.copyOf(thresholds);}
 public GateResult evaluate(Map<String,Double> metrics){List<String> failed=thresholds.entrySet().stream().filter(e->metrics.getOrDefault(e.getKey(),Double.NEGATIVE_INFINITY)<e.getValue()).map(Map.Entry::getKey).sorted().toList();return new GateResult(failed.isEmpty(),failed);}
 public record GateResult(boolean passed,List<String> failedMetrics){}
}
