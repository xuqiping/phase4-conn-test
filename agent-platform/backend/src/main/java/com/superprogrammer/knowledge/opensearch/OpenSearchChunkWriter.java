package com.superprogrammer.knowledge.opensearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnBean(RestClient.class)
public class OpenSearchChunkWriter {

    private final BulkGateway gateway;
    private final ObjectMapper objectMapper;

    public OpenSearchChunkWriter(RestClient client, ObjectMapper objectMapper) {
        this(new RestBulkGateway(client, objectMapper), objectMapper);
    }

    OpenSearchChunkWriter(BulkGateway gateway) {
        this(gateway, new ObjectMapper());
    }

    private OpenSearchChunkWriter(BulkGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public void write(String alias, List<OpenSearchChunkDocument> documents) {
        List<BulkOperation> operations = documents.stream()
                .map(document -> new BulkOperation(String.valueOf(document.nodeId()), toJson(document)))
                .toList();
        try {
            List<BulkFailure> failures = gateway.bulk(alias, operations);
            if (!failures.isEmpty()) {
                throw new PartialBulkFailure(failures.stream().map(BulkFailure::id).toList());
            }
        } catch (IOException error) {
            throw new IllegalStateException("OpenSearch bulk request failed: " + error.getClass().getSimpleName(), error);
        }
    }

    private String toJson(OpenSearchChunkDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("OpenSearch chunk serialization failed", error);
        }
    }

    public interface BulkGateway {
        List<BulkFailure> bulk(String alias, List<BulkOperation> operations) throws IOException;
    }

    public record BulkOperation(String id, String json) {}
    public record BulkFailure(String id, int status, String errorType) {}

    public static class PartialBulkFailure extends RuntimeException {
        private final List<String> failedIds;
        PartialBulkFailure(List<String> failedIds) {
            super("OpenSearch bulk contained failed item ids=" + failedIds);
            this.failedIds = List.copyOf(failedIds);
        }
        public List<String> failedIds() { return failedIds; }
    }

    private record RestBulkGateway(RestClient client, ObjectMapper objectMapper) implements BulkGateway {
        @Override
        public List<BulkFailure> bulk(String alias, List<BulkOperation> operations) throws IOException {
            StringBuilder ndjson = new StringBuilder();
            for (BulkOperation operation : operations) {
                ndjson.append("{\"index\":{\"_index\":\"").append(alias)
                        .append("\",\"_id\":\"").append(operation.id()).append("\"}}\n")
                        .append(operation.json()).append('\n');
            }
            Request request = new Request("POST", "/_bulk");
            request.setEntity(new NStringEntity(ndjson.toString(), ContentType.create("application/x-ndjson", StandardCharsets.UTF_8)));
            Response response = client.performRequest(request);
            Map<?, ?> payload = objectMapper.readValue(response.getEntity().getContent(), Map.class);
            List<BulkFailure> failures = new ArrayList<>();
            Object rawItems = payload.get("items");
            List<?> items = rawItems instanceof List<?> list ? list : List.of();
            for (Object raw : items) {
                Map<?, ?> item = (Map<?, ?>) ((Map<?, ?>) raw).get("index");
                Object rawStatus = item.get("status");
                int status = rawStatus instanceof Number number ? number.intValue() : 500;
                if (status >= 300) {
                    failures.add(new BulkFailure(String.valueOf(item.get("_id")), status, "bulk_item_failed"));
                }
            }
            return failures;
        }
    }
}
