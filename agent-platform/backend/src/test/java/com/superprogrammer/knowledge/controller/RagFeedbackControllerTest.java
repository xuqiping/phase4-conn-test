package com.superprogrammer.knowledge.controller;

import com.superprogrammer.knowledge.dto.RagFeedbackRequest;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import com.superprogrammer.knowledge.evaluation.FeedbackReviewService;
import com.superprogrammer.knowledge.service.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.Mockito.*;

class RagFeedbackControllerTest {
    @Test
    void submitsFeedbackWithKnowledgeBaseTenantInsteadOfStaticZero() {
        FeedbackReviewService feedback = mock(FeedbackReviewService.class);
        KnowledgeBaseService bases = mock(KnowledgeBaseService.class);
        KnowledgeBase kb = new KnowledgeBase(); kb.setId(9L); kb.setTenantId(4L);
        when(bases.ensure(9L)).thenReturn(kb);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, List.of()));
        RagFeedbackController controller = new RagFeedbackController(feedback, bases);

        controller.submit(new RagFeedbackRequest(9L, 3L, "NOT_RELEVANT", "说明"));

        verify(feedback).submit(4L, 9L, 3L, "NOT_RELEVANT", "说明", 7L);
    }
}
