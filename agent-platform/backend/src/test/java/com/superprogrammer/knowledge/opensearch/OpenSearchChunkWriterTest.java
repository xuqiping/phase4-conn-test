package com.superprogrammer.knowledge.opensearch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenSearchChunkWriterTest {

    @Test
    void writesDenseSparseAndAuthorityMetadataWithStableNodeId() {
        RecordingBulkGateway gateway = new RecordingBulkGateway(List.of());
        OpenSearchChunkWriter writer = new OpenSearchChunkWriter(gateway);
        OpenSearchChunkDocument document = document(7L);

        writer.write("kb-42-chunks-write", List.of(document));

        assertEquals("7", gateway.operations.get(0).id());
        String json = gateway.operations.get(0).json();
        assertTrue(json.contains("\"denseVector\":[0.1,0.2]"));
        assertTrue(json.contains("\"sparseText\":\"context content\""));
        assertTrue(json.contains("\"documentVersionId\":9"));
        assertTrue(json.contains("\"aclTokens\":[\"tenant:1\",\"kb:42\"]"));
    }

    @Test
    void throwsOnlyForFailedBulkItemsSoJobCanRetryIdempotently() {
        RecordingBulkGateway gateway = new RecordingBulkGateway(List.of(
                new OpenSearchChunkWriter.BulkFailure("8", 429, "rejected")));
        OpenSearchChunkWriter writer = new OpenSearchChunkWriter(gateway);

        OpenSearchChunkWriter.PartialBulkFailure failure = assertThrows(
                OpenSearchChunkWriter.PartialBulkFailure.class,
                () -> writer.write("kb-42-chunks-write", List.of(document(7L), document(8L))));

        assertEquals(List.of("8"), failure.failedIds());
        assertFalse(failure.getMessage().contains("context content"));
    }

    private static OpenSearchChunkDocument document(long nodeId) {
        return new OpenSearchChunkDocument(1L, 42L, 5L, 9L, nodeId,
                List.of("tenant:1", "kb:42"), "ACTIVE", "content-hash", "context-hash",
                "第1章 定位语", "pipeline-v2", "context content", new float[]{0.1f, 0.2f});
    }

    private static class RecordingBulkGateway implements OpenSearchChunkWriter.BulkGateway {
        private final List<OpenSearchChunkWriter.BulkFailure> failures;
        private List<OpenSearchChunkWriter.BulkOperation> operations;

        private RecordingBulkGateway(List<OpenSearchChunkWriter.BulkFailure> failures) {
            this.failures = failures;
        }

        @Override
        public List<OpenSearchChunkWriter.BulkFailure> bulk(String alias,
                                                            List<OpenSearchChunkWriter.BulkOperation> operations) {
            this.operations = operations;
            return failures;
        }
    }
}
