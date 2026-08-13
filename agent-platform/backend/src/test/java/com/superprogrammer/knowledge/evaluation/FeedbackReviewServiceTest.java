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

    @Test
    void onlyApprovedFeedbackCanBecomeGoldenCandidate() {
        java.util.List<FeedbackReviewService.Feedback> updated = new java.util.ArrayList<>();
        FeedbackReviewService service = new FeedbackReviewService(new FeedbackReviewService.FeedbackStore() {
            public void save(FeedbackReviewService.Feedback feedback) {}
            public FeedbackReviewService.Feedback find(long tenantId,long id) {
                return new FeedbackReviewService.Feedback(id,tenantId,9L,null,"NOT_RELEVANT",null,"PENDING",7L,null,null);
            }
            public void update(FeedbackReviewService.Feedback feedback) { updated.add(feedback); }
        }, null);

        FeedbackReviewService.Feedback approved=service.review(4L,3L,true,11L);

        assertEquals("APPROVED",approved.status());
        assertTrue(service.canEnterGoldenSet(approved));
        assertEquals(11L,approved.reviewedBy());
    }
}
