package com.superprogrammer.knowledge.evaluation;

import com.superprogrammer.common.metrics.BizMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class FeedbackReviewService {
    private static final Set<String> CATEGORIES = Set.of(
            "NOT_RELEVANT", "OUTDATED", "WRONG_CITATION", "INCOMPLETE");
    private final FeedbackSink sink;
    private final BizMetrics metrics;
    private final AtomicLong ids = new AtomicLong();

    public FeedbackReviewService() {
        this((FeedbackSink) feedback -> {}, null);
    }

    @Autowired
    public FeedbackReviewService(FeedbackReviewMapper mapper, BizMetrics metrics) {
        this((FeedbackSink) mapper::insert, metrics);
    }

    FeedbackReviewService(FeedbackSink sink, BizMetrics metrics) {
        this.sink = sink;
        this.metrics = metrics;
    }

    public Feedback submit(long knowledgeBaseId, Long evaluationResultId, String category,
                           String comment, long submittedBy) {
        return submit(0L, knowledgeBaseId, evaluationResultId, category, comment, submittedBy);
    }

    public Feedback submit(long tenantId, long knowledgeBaseId, Long evaluationResultId, String category,
                           String comment, long submittedBy) {
        String normalizedCategory = category == null ? "" : category.trim().toUpperCase();
        if (!CATEGORIES.contains(normalizedCategory)) {
            throw new IllegalArgumentException("反馈分类不合法");
        }
        String normalizedComment = comment == null ? null : comment.trim();
        if (normalizedComment != null && normalizedComment.length() > 1000) {
            throw new IllegalArgumentException("反馈说明不能超过 1000 字符");
        }
        Feedback feedback = new Feedback(ids.incrementAndGet(), tenantId, knowledgeBaseId,
                evaluationResultId, normalizedCategory, normalizedComment, "PENDING", submittedBy);
        sink.save(feedback);
        if (metrics != null) metrics.ragFeedback(normalizedCategory, "pending");
        return feedback;
    }

    public boolean affectsRanking(long feedbackId) {
        return false;
    }

    @FunctionalInterface
    interface FeedbackSink { void save(Feedback feedback); }

    public static final class Feedback {
        private Long id;
        private final long tenantId;
        private final long knowledgeBaseId;
        private final Long evaluationResultId;
        private final String category;
        private final String comment;
        private final String status;
        private final long submittedBy;

        Feedback(Long id, long tenantId, long knowledgeBaseId, Long evaluationResultId,
                 String category, String comment, String status, long submittedBy) {
            this.id = id;
            this.tenantId = tenantId;
            this.knowledgeBaseId = knowledgeBaseId;
            this.evaluationResultId = evaluationResultId;
            this.category = category;
            this.comment = comment;
            this.status = status;
            this.submittedBy = submittedBy;
        }

        public Long id() { return id; }
        public long tenantId() { return tenantId; }
        public long knowledgeBaseId() { return knowledgeBaseId; }
        public Long evaluationResultId() { return evaluationResultId; }
        public String category() { return category; }
        public String comment() { return comment; }
        public String status() { return status; }
        public long submittedBy() { return submittedBy; }
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public long getTenantId() { return tenantId; }
        public long getKnowledgeBaseId() { return knowledgeBaseId; }
        public Long getEvaluationResultId() { return evaluationResultId; }
        public String getCategory() { return category; }
        public String getComment() { return comment; }
        public String getStatus() { return status; }
        public long getSubmittedBy() { return submittedBy; }
    }
}
