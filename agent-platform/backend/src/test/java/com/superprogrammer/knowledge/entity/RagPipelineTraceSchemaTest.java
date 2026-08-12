package com.superprogrammer.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RAG-FR-08/09：Pipeline 与全链路运行记录必须由 Flyway 和实体映射共同定义。 */
class RagPipelineTraceSchemaTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V101__rag_pipeline_trace_foundation.sql");

    @Test
    void migrationDefinesPipelineAndTraceTables() throws Exception {
        assertTrue(Files.exists(MIGRATION), "V101 RAG trace migration must exist");
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8).toLowerCase();
        for (String table : Map.of(
                "rag_pipeline_versions", "pipeline",
                "rag_retrieval_runs", "retrieval",
                "rag_ranking_runs", "ranking",
                "rag_model_calls", "model call",
                "rag_fallback_events", "fallback").keySet()) {
            assertTrue(sql.contains("create table " + table), "missing table: " + table);
        }
        assertTrue(sql.contains("provider_request_id"));
        assertTrue(sql.contains("call_purpose"));
        assertTrue(sql.contains("configured_mode"));
        assertTrue(sql.contains("effective_mode"));
    }

    @Test
    void entitiesMapToTraceTables() throws Exception {
        assertTable("com.superprogrammer.knowledge.entity.RagPipelineVersion", "rag_pipeline_versions");
        assertTable("com.superprogrammer.knowledge.entity.RagRetrievalRun", "rag_retrieval_runs");
        assertTable("com.superprogrammer.knowledge.entity.RagRankingRun", "rag_ranking_runs");
        assertTable("com.superprogrammer.knowledge.entity.RagModelCall", "rag_model_calls");
        assertTable("com.superprogrammer.knowledge.entity.RagFallbackEvent", "rag_fallback_events");
    }

    private static void assertTable(String className, String expectedTable) throws Exception {
        Class<?> type = Class.forName(className);
        TableName tableName = type.getAnnotation(TableName.class);
        assertEquals(expectedTable, tableName.value());
    }
}
