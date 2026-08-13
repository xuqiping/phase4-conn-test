package com.superprogrammer.knowledge.opensearch;

import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.opensearch.client.Request;
import org.opensearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@ConditionalOnBean(RestClient.class)
public class KnowledgeIndexManager {

    private final RestClient client;

    public KnowledgeIndexManager(RestClient client) {
        this.client = client;
    }

    public String create(long knowledgeBaseId, KnowledgeIndexSchema schema) throws IOException {
        String index = schema.physicalIndexName(knowledgeBaseId);
        Request request = new Request("PUT", "/" + index);
        request.setEntity(new NStringEntity(schema.mappingJson(), ContentType.APPLICATION_JSON));
        client.performRequest(request);
        return index;
    }

    public boolean exists(String physicalIndex) throws IOException {
        Request request = new Request("HEAD", "/" + physicalIndex);
        return client.performRequest(request).getStatusLine().getStatusCode() == 200;
    }
}
