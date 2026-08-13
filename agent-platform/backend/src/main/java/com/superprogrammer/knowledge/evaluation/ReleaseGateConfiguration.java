package com.superprogrammer.knowledge.evaluation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class ReleaseGateConfiguration {
    @Bean
    public ReleaseGateService releaseGateService(
            @Value("${rag.evaluation.release-gate.recall:0.92}") double recall,
            @Value("${rag.evaluation.release-gate.citation-coverage:0.95}") double citationCoverage,
            @Value("${rag.evaluation.release-gate.faithfulness:0.95}") double faithfulness,
            @Value("${rag.evaluation.release-gate.abstention-accuracy:0.90}") double abstentionAccuracy) {
        return new ReleaseGateService(Map.of("recall",recall,"citationCoverage",citationCoverage,
                "faithfulness",faithfulness,"abstentionAccuracy",abstentionAccuracy));
    }
}
