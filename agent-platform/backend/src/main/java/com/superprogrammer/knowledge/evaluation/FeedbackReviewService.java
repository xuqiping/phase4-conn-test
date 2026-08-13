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
    private final FeedbackStore store;
    private final BizMetrics metrics;
    private final AtomicLong ids = new AtomicLong();

    public FeedbackReviewService() {
        this(new FeedbackStore(){public void save(Feedback feedback){} public Feedback find(long tenantId,long id){return null;} public void update(Feedback feedback){}}, null);
    }

    @Autowired
    public FeedbackReviewService(FeedbackReviewMapper mapper, BizMetrics metrics) {
        this(new FeedbackStore(){public void save(Feedback f){mapper.insert(f);} public Feedback find(long t,long id){return mapper.find(t,id);} public void update(Feedback f){if(mapper.updateReview(f)!=1)throw new IllegalStateException("反馈已审核或不存在");}}, metrics);
    }

    FeedbackReviewService(FeedbackStore store, BizMetrics metrics) {
        this.store = store;
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
        store.save(feedback);
        if (metrics != null) metrics.ragFeedback(normalizedCategory, "pending");
        return feedback;
    }

    public boolean affectsRanking(long feedbackId) {
        return false;
    }

    public Feedback review(long tenantId,long feedbackId,boolean approved,long reviewedBy) {
        Feedback current=store.find(tenantId,feedbackId);
        if(current==null)throw new IllegalArgumentException("反馈不存在");
        if(!"PENDING".equals(current.status()))throw new IllegalStateException("反馈已审核");
        Feedback reviewed=current.reviewed(approved?"APPROVED":"REJECTED",reviewedBy,java.time.OffsetDateTime.now());
        store.update(reviewed);
        if(metrics!=null)metrics.ragFeedback(reviewed.category(),reviewed.status().toLowerCase());
        return reviewed;
    }
    public boolean canEnterGoldenSet(Feedback feedback){return feedback!=null && "APPROVED".equals(feedback.status());}

    interface FeedbackStore { void save(Feedback feedback); Feedback find(long tenantId,long id); void update(Feedback feedback); }

    public static final class Feedback {
        private Long id;
        private final long tenantId;
        private final long knowledgeBaseId;
        private final Long evaluationResultId;
        private final String category;
        private final String comment;
        private final String status;
        private final long submittedBy;
        private final Long reviewedBy;
        private final java.time.OffsetDateTime reviewedAt;

        Feedback(Long id, long tenantId, long knowledgeBaseId, Long evaluationResultId,
                 String category, String comment, String status, long submittedBy) {
            this(id,tenantId,knowledgeBaseId,evaluationResultId,category,comment,status,submittedBy,null,null);
        }
        Feedback(Long id, long tenantId, long knowledgeBaseId, Long evaluationResultId,
                 String category, String comment, String status, long submittedBy,Long reviewedBy,java.time.OffsetDateTime reviewedAt) {
            this.id = id;
            this.tenantId = tenantId;
            this.knowledgeBaseId = knowledgeBaseId;
            this.evaluationResultId = evaluationResultId;
            this.category = category;
            this.comment = comment;
            this.status = status;
            this.submittedBy = submittedBy;
            this.reviewedBy=reviewedBy; this.reviewedAt=reviewedAt;
        }

        public Long id() { return id; }
        public long tenantId() { return tenantId; }
        public long knowledgeBaseId() { return knowledgeBaseId; }
        public Long evaluationResultId() { return evaluationResultId; }
        public String category() { return category; }
        public String comment() { return comment; }
        public String status() { return status; }
        public long submittedBy() { return submittedBy; }
        public Long reviewedBy(){return reviewedBy;} public java.time.OffsetDateTime reviewedAt(){return reviewedAt;}
        Feedback reviewed(String status,long reviewedBy,java.time.OffsetDateTime reviewedAt){return new Feedback(id,tenantId,knowledgeBaseId,evaluationResultId,category,comment,status,submittedBy,reviewedBy,reviewedAt);}
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public long getTenantId() { return tenantId; }
        public long getKnowledgeBaseId() { return knowledgeBaseId; }
        public Long getEvaluationResultId() { return evaluationResultId; }
        public String getCategory() { return category; }
        public String getComment() { return comment; }
        public String getStatus() { return status; }
        public long getSubmittedBy() { return submittedBy; }
        public Long getReviewedBy(){return reviewedBy;} public java.time.OffsetDateTime getReviewedAt(){return reviewedAt;}
    }
}
