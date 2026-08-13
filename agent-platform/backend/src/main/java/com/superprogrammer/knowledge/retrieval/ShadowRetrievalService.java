package com.superprogrammer.knowledge.retrieval;
import java.util.function.Supplier;
public class ShadowRetrievalService{
 public ShadowResult run(boolean sampled,double budgetRemaining,Supplier<Object> challenger){if(!sampled)return new ShadowResult("SKIPPED",null);if(budgetRemaining<=0)return new ShadowResult("BUDGET_EXHAUSTED",null);try{return new ShadowResult("SUCCEEDED",challenger.get());}catch(RuntimeException e){return new ShadowResult("FAILED",null);}}
 public record ShadowResult(String status,Object result){}
}
