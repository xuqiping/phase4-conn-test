package com.superprogrammer.knowledge.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationSchemaTest {
    @Test
    void evaluationSchemaSupportsAnswerabilityAndTenantSafeLookup() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V115__rag_evaluation_center.sql"));
        assertTrue(sql.contains("answerable BOOLEAN NOT NULL"));
        assertTrue(sql.contains("UNIQUE (tenant_id, kb_id, name)"));
        assertTrue(sql.contains("idx_rag_eval_run_status"));
    }
}
