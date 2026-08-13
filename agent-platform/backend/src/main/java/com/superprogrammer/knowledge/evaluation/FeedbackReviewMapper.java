package com.superprogrammer.knowledge.evaluation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface FeedbackReviewMapper {
    @Insert("""
            INSERT INTO rag_feedback_reviews
                (tenant_id, kb_id, eval_result_id, category, comment, status, submitted_by)
            VALUES
                (#{tenantId}, #{knowledgeBaseId}, #{evaluationResultId}, #{category}, #{comment}, #{status}, #{submittedBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(FeedbackReviewService.Feedback feedback);
}
