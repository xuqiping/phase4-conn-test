package com.superprogrammer.knowledge.retrieval;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Executes sampled challenger retrieval without affecting the champion response. */
public class ShadowRetrievalService {
    private final ExecutorService executor;
    private final Sink sink;

    public ShadowRetrievalService(ExecutorService executor, Sink sink) {
        this.executor = executor;
        this.sink = sink;
    }

    public ShadowResult run(ShadowRequest request, Supplier<ChallengerResult> challenger) {
        if (!request.sampled()) return persist(request,"SKIPPED",null,null);
        if (request.budgetRemaining() <= 0) return persist(request,"BUDGET_EXHAUSTED",null,null);
        var future=executor.submit(challenger::get);
        try {
            ChallengerResult result=future.get(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if(result.cost()>request.budgetRemaining()) return persist(request,"BUDGET_EXCEEDED",null,null);
            return persist(request,"SUCCEEDED",result,null);
        } catch (TimeoutException timeout) {
            future.cancel(true); return persist(request,"TIMED_OUT",null,"timeout");
        } catch (Exception error) {
            return persist(request,"FAILED",null,error.getClass().getSimpleName());
        }
    }

    private ShadowResult persist(ShadowRequest request,String status,ChallengerResult result,String error) {
        ShadowRecord record=new ShadowRecord(0L,request.tenantId(),request.kbId(),request.userId(),
                request.championTraceId(),result==null?null:result.traceId(),request.championVersion(),
                request.challengerVersion(),status,result==null?List.of():result.rankedChunkIds(),
                result==null?0:result.cost(),error,OffsetDateTime.now());
        sink.save(record);
        return new ShadowResult(status,record);
    }

    @FunctionalInterface public interface Sink { void save(ShadowRecord record); }
    public record ShadowRequest(long tenantId,long kbId,long userId,String championTraceId,
                                String championVersion,String challengerVersion,boolean sampled,
                                double budgetRemaining,Duration timeout) {}
    public record ChallengerResult(String traceId,List<String> rankedChunkIds,double cost) {}
    public record ShadowRecord(long id,long tenantId,long kbId,long userId,String championTraceId,
                               String challengerTraceId,String championVersion,String challengerVersion,
                               String status,List<String> rankedChunkIds,double cost,String errorSummary,
                               OffsetDateTime createdAt) {}
    public record ShadowResult(String status,ShadowRecord record) {}
}
