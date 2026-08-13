package com.superprogrammer.knowledge.opensearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeIndexSchemaTest {

    @Test
    void buildsVersionedPhysicalNameWithoutModelNickname() {
        KnowledgeIndexSchema schema = new KnowledgeIndexSchema(1536, "pipeline-v2", "snapshot-17");

        String name = schema.physicalIndexName(42L);

        assertEquals("kb-42-chunks-snapshot-17-pipeline-v2", name);
        assertFalse(name.contains("doubao"));
        assertFalse(name.contains("text-embedding"));
    }

    @Test
    void rejectsVectorDimensionMismatchBeforeIndexCreation() {
        KnowledgeIndexSchema schema = new KnowledgeIndexSchema(1536, "pipeline-v2", "snapshot-17");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> schema.validateEmbeddingDimension(1024));

        assertTrue(error.getMessage().contains("1536"));
        assertTrue(error.getMessage().contains("1024"));
    }

    @Test
    void mappingContainsMandatoryAclVersionAndRetrievalFields() {
        KnowledgeIndexSchema schema = new KnowledgeIndexSchema(1536, "pipeline-v2", "snapshot-17");

        String mapping = schema.mappingJson();

        for (String field : new String[]{"tenantId", "knowledgeBaseId", "aclTokens", "status",
                "documentVersionId", "contentHash", "denseVector", "sparseText"}) {
            assertTrue(mapping.contains("\"" + field + "\""), field);
        }
        assertTrue(mapping.contains("\"dimension\":1536"));
    }
}
