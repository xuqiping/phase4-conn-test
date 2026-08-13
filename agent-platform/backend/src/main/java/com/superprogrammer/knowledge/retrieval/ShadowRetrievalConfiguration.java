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
    @Bean
    public ShadowRetrievalService shadowRetrievalService(java.util.concurrent.ExecutorService ragShadowExecutor,
                                                         PostgresShadowSink sink) {
        return new ShadowRetrievalService(ragShadowExecutor,sink);
    }
}
