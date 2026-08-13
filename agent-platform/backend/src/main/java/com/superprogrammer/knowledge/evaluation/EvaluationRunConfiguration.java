package com.superprogrammer.knowledge.evaluation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class EvaluationRunConfiguration {
    @Bean("ragEvaluationExecutor")
    public Executor ragEvaluationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("rag-eval-");
        executor.initialize();
        return executor;
    }

    @Bean
    public EvaluationRunService evaluationRunService(PostgresEvaluationRepository repository,
                                                     RagEvaluationPipeline pipeline,
                                                     @Qualifier("ragEvaluationExecutor") Executor ragEvaluationExecutor) {
        return new EvaluationRunService(repository, ragEvaluationExecutor, pipeline);
    }
}
