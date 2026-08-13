package com.superprogrammer.knowledge.evaluation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Select("SELECT * FROM rag_feedback_reviews WHERE tenant_id=#{tenantId} AND id=#{id}")
    FeedbackReviewService.Feedback find(@Param("tenantId") long tenantId,@Param("id") long id);

    @Update("""
            UPDATE rag_feedback_reviews SET status=#{status},reviewed_by=#{reviewedBy},reviewed_at=#{reviewedAt}
            WHERE tenant_id=#{tenantId} AND id=#{id} AND status='PENDING'
            """)
    int updateReview(FeedbackReviewService.Feedback feedback);
}
