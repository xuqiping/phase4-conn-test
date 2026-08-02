package com.superprogrammer.runtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.runtime.config.RuntimeGatewayProperties;
import com.superprogrammer.runtime.dto.ExecutionEvent;
import com.superprogrammer.runtime.dto.ExecutionRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "runtime.gateway", name = "mode", havingValue = "sidecar", matchIfMissing = true)
public class SidecarRuntimeGateway implements RuntimeGateway {

    private final RuntimeGatewayProperties properties;
    private final ObjectMapper objectMapper;

    public SidecarRuntimeGateway(RuntimeGatewayProperties properties) {
        this(properties, new ObjectMapper().findAndRegisterModules());
    }

    @Autowired
    public SidecarRuntimeGateway(RuntimeGatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<ExecutionEvent> run(ExecutionRequest request) {
        WebClient client = WebClient.builder()
                .baseUrl(normalizeBaseUrl(properties.getSidecarBaseUrl()))
                .build();

        return client.post()
                .uri("/api/runtime/executions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new IllegalStateException(
                                "Runtime sidecar request failed with status "
                                        + response.statusCode().value()
                                        + (body.isBlank() ? "" : ": " + body)))))
                .bodyToFlux(DataBuffer.class)
                .transform(this::decodeSseData)
                .map(this::parseEvent);
    }

    private Flux<String> decodeSseData(Flux<DataBuffer> buffers) {
        return Flux.defer(() -> {
            StringBuilder pending = new StringBuilder();
            return buffers.concatMapIterable(buffer -> {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                DataBufferUtils.release(buffer);
                pending.append(new String(bytes, StandardCharsets.UTF_8));
                return drainCompleteSsePayloads(pending);
            });
        });
    }

    private List<String> drainCompleteSsePayloads(StringBuilder pending) {
        List<String> payloads = new ArrayList<>();
        while (true) {
            int delimiter = sseDelimiterIndex(pending);
            if (delimiter < 0) {
                return payloads;
            }
            String rawEvent = pending.substring(0, delimiter);
            int removeLength = delimiter;
            while (removeLength < pending.length()
                    && (pending.charAt(removeLength) == '\r' || pending.charAt(removeLength) == '\n')) {
                removeLength++;
            }
            pending.delete(0, removeLength);
            String payload = sseData(rawEvent);
            if (!payload.isBlank() && !"[DONE]".equals(payload)) {
                payloads.add(payload);
            }
        }
    }

    private int sseDelimiterIndex(StringBuilder value) {
        int lf = value.indexOf("\n\n");
        int crlf = value.indexOf("\r\n\r\n");
        if (lf < 0) {
            return crlf;
        }
        if (crlf < 0) {
            return lf;
        }
        return Math.min(lf, crlf);
    }

    private String sseData(String rawEvent) {
        List<String> lines = new ArrayList<>();
        for (String line : rawEvent.split("\\r?\\n")) {
            if (line.startsWith("data:")) {
                lines.add(line.substring(5).stripLeading());
            }
        }
        return String.join("\n", lines);
    }

    private ExecutionEvent parseEvent(String payload) {
        try {
            return objectMapper.readValue(payload, ExecutionEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse runtime sidecar event: " + payload, e);
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8090";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
