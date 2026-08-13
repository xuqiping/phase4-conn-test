package com.superprogrammer.knowledge.answer;
public class ConfidenceEvaluator{
 private final double supported; private final double partial;
 public ConfidenceEvaluator(double supported,double partial){if(partial>supported)throw new IllegalArgumentException("partial threshold must <= supported");this.supported=supported;this.partial=partial;}
 public String evaluate(double score,boolean hasEvidence,boolean conflict){if(conflict)return "CONFLICT";if(!hasEvidence)return "INSUFFICIENT";if(score>=supported)return "SUPPORTED";if(score>=partial)return "PARTIAL";return "INSUFFICIENT";}
 public String outOfScope(){return "OUT_OF_SCOPE";} public String retrievalFailed(){return "RETRIEVAL_FAILED";}
}
