package com.superprogrammer.knowledge.evaluation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackReviewServiceTest {

    @Test
    void feedbackAlwaysEntersPendingQueueAndCannotChangeRankingDirectly() {
        FeedbackReviewService service = new FeedbackReviewService();

        FeedbackReviewService.Feedback feedback = service.submit(
                8L, 19L, "NOT_RELEVANT", "引用不准确", 33L);

        assertEquals("PENDING", feedback.status());
        assertEquals("引用不准确", feedback.comment());
        assertFalse(service.affectsRanking(feedback.id()));
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(8L, 19L, "FREE_FORM", "x", 33L));
    }
}
