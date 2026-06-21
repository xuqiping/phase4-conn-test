package com.superprogrammer.runtime.service;

import com.superprogrammer.runtime.config.RuntimeGatewayProperties;
import com.superprogrammer.runtime.dto.ExecutionEvent;
import com.superprogrammer.runtime.dto.ExecutionRequest;
import com.superprogrammer.runtime.dto.RuntimeEdge;
import com.superprogrammer.runtime.dto.RuntimeNode;
import com.superprogrammer.runtime.dto.RuntimeNodeType;
import com.superprogrammer.runtime.dto.WorkflowDefinition;
import com.sun.net.httpserver.HttpServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SidecarRuntimeGatewayTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void runPostsExecutionRequestAndParsesSseEvents() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        event: EXECUTION_STARTED
                        data: {"executionId":"1001","rootExecutionId":"1001","type":"EXECUTION_STARTED","status":"RUNNING","metadata":{"traceId":"trace-1001","externalThreadId":"sidecar-thread-1001"},"timestamp":"2026-06-03T06:00:00Z"}

                        event: EXECUTION_COMPLETED
                        data: {"executionId":"1001","rootExecutionId":"1001","type":"EXECUTION_COMPLETED","status":"SUCCESS","metadata":{"traceId":"trace-1001","externalThreadId":"sidecar-thread-1001"},"timestamp":"2026-06-03T06:00:01Z"}

                        """));
        SidecarRuntimeGateway gateway = gateway();

        StepVerifier.create(gateway.run(request()))
                .expectNextMatches(event -> event.getType().equals("EXECUTION_STARTED")
                        && event.getMetadata().get("externalThreadId").equals("sidecar-thread-1001"))
                .expectNextMatches(event -> event.getType().equals("EXECUTION_COMPLETED")
                        && event.getStatus().equals("SUCCESS"))
                .verifyComplete();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/api/runtime/executions");
        assertThat(recorded.getHeader("Accept")).contains("text/event-stream");
        assertThat(recorded.getBody().readUtf8()).contains("\"executionId\":\"1001\"");
    }

    @Test
    void runEmitsFirstSseEventBeforeSidecarResponseCompletes() {
        String firstEvent = """
                event: EXECUTION_STARTED
                data: {"executionId":"1001","rootExecutionId":"1001","type":"EXECUTION_STARTED","status":"RUNNING","metadata":{"traceId":"trace-1001"},"timestamp":"2026-06-03T06:00:00Z"}

                """;
        String secondEvent = """
                event: EXECUTION_COMPLETED
                data: {"executionId":"1001","rootExecutionId":"1001","type":"EXECUTION_COMPLETED","status":"SUCCESS","metadata":{"traceId":"trace-1001"},"timestamp":"2026-06-03T06:00:01Z"}

                """;
        HttpServer streamingServer = streamingServer(firstEvent, secondEvent);
        SidecarRuntimeGateway gateway = gateway("http://localhost:" + streamingServer.getAddress().getPort());

        try {
            StepVerifier.create(gateway.run(request()))
                    .expectNextMatches(event -> event.getType().equals("EXECUTION_STARTED"))
                    .expectNoEvent(Duration.ofMillis(500))
                    .thenCancel()
                    .verify(Duration.ofSeconds(3));
        } finally {
            streamingServer.stop(0);
        }
    }

    @Test
    void runPropagatesSidecarHttpErrors() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"boom\"}"));
        SidecarRuntimeGateway gateway = gateway();

        StepVerifier.create(gateway.run(request()))
                .expectErrorMatches(error -> error.getMessage().contains("Runtime sidecar request failed"))
                .verify();
    }

    private SidecarRuntimeGateway gateway() {
        return gateway(server.url("/").toString());
    }

    private SidecarRuntimeGateway gateway(String baseUrl) {
        RuntimeGatewayProperties properties = new RuntimeGatewayProperties();
        properties.setSidecarBaseUrl(baseUrl);
        return new SidecarRuntimeGateway(properties);
    }

    private HttpServer streamingServer(String firstEvent, String secondEvent) {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            httpServer.createContext("/api/runtime/executions", exchange -> {
                byte[] first = firstEvent.getBytes(StandardCharsets.UTF_8);
                byte[] second = secondEvent.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(first);
                    body.flush();
                    Thread.sleep(1500);
                    body.write(second);
                    body.flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            httpServer.start();
            return httpServer;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start streaming test server", e);
        }
    }

    private ExecutionRequest request() {
        return ExecutionRequest.builder()
                .executionId("1001")
                .rootExecutionId("1001")
                .sourceType("WORKFLOW")
                .sourceId(42L)
                .userId(7L)
                .workflow(WorkflowDefinition.builder()
                        .version("2026-06-03")
                        .workflowId(42L)
                        .name("sidecar smoke workflow")
                        .nodes(List.of(
                                RuntimeNode.builder()
                                        .id("start-1")
                                        .type(RuntimeNodeType.START)
                                        .label("Start")
                                        .config(Map.of())
                                        .build(),
                                RuntimeNode.builder()
                                        .id("end-1")
                                        .type(RuntimeNodeType.END)
                                        .label("End")
                                        .config(Map.of())
                                        .build()
                        ))
                        .edges(List.of(RuntimeEdge.builder()
                                .source("start-1")
                                .target("end-1")
                                .build()))
                        .build())
                .input(Map.of("message", "hello"))
                .runtime(Map.of("stream", true, "traceId", "trace-1001"))
                .build();
    }
}
