package com.superprogrammer.knowledge.retrieval;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
public class ShadowRetrievalConfiguration {
    @Bean(destroyMethod = "shutdown")
    public java.util.concurrent.ExecutorService ragShadowExecutor() {
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread=new Thread(runnable,"rag-shadow"); thread.setDaemon(true); return thread;
        });
    }
    @Bean(destroyMethod = "shutdown")
    public java.util.concurrent.ExecutorService ragShadowTriggerExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rag-shadow-trigger"); thread.setDaemon(true); return thread;
        });
    }
    @Bean
    public ShadowRetrievalService shadowRetrievalService(java.util.concurrent.ExecutorService ragShadowExecutor,
                                                         PostgresShadowSink sink) {
        return new ShadowRetrievalService(ragShadowExecutor,sink);
    }
    @Bean
    public RagShadowCoordinator ragShadowCoordinator(ShadowRetrievalService service,
            com.superprogrammer.knowledge.migration.RagRolloutService rolloutService,
            java.util.concurrent.ExecutorService ragShadowTriggerExecutor, RagShadowProperties properties) {
        return new RagShadowCoordinator(service, rolloutService, ragShadowTriggerExecutor, properties);
    }
}
