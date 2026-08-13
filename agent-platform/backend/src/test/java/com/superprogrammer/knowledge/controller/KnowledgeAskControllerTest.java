package com.superprogrammer.knowledge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.knowledge.dto.AskRequest;
import com.superprogrammer.knowledge.dto.EvidenceResult;
import com.superprogrammer.knowledge.service.RagConfig;
import com.superprogrammer.knowledge.service.RagRetrievalService;
import com.superprogrammer.knowledge.service.RagScopeResolver;
import com.superprogrammer.llm.LlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class KnowledgeAskControllerTest {
    @Test
    void emitsVerifiedGroundedAnswerThenCitationStateAndDone() {
        RagScopeResolver scope = mock(RagScopeResolver.class);
        RagRetrievalService retrieval = mock(RagRetrievalService.class);
        LlmGateway gateway = mock(LlmGateway.class);
        KnowledgeAskController controller = new KnowledgeAskController(
                scope, retrieval, gateway, new RagConfig(), new ObjectMapper());
        AskRequest request = new AskRequest();
        request.setQuery("如何安装");
        request.setKbIds(List.of(1L));
        when(scope.resolveEffectiveKbs("CHAT", List.of(1L), null, null, 7L, false)).thenReturn(List.of(1L));
        EvidenceResult evidence = EvidenceResult.builder().systemPrompt("[1] 安装说明")
                .injectedIndexes(Set.of(1)).citations(List.of()).abstained(false).build();
        when(retrieval.retrieveGroundedAnswer(List.of(1L), "如何安装", 7L, false)).thenReturn(
                new RagRetrievalService.GroundedAskResult(evidence, "按步骤安装 [1]", "SUPPORTED"));

        List<StreamEvent> events = controller.buildAskFlux(request, 7L, false).collectList().block();

        assertEquals(List.of("CHUNK", "CITATION", "RAG_STATE", "DONE"),
                events.stream().map(StreamEvent::getType).toList());
        assertEquals("SUPPORTED", events.get(2).getContent());
        verify(gateway, never()).chatStream(any(), any());
    }
}
