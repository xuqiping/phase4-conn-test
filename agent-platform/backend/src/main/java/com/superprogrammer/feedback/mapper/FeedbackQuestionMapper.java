package com.superprogrammer.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.feedback.entity.FeedbackQuestionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 提问台 Mapper。回答/关闭走条件 UPDATE 抢态。
 */
@Mapper
public interface FeedbackQuestionMapper extends BaseMapper<FeedbackQuestionEntity> {

    /**
     * 回答抢态：OPEN→ANSWERED（首答）或 ANSWERED→ANSWERED（改答案，service 层据此判断发不发通知）。
     * CLOSED 不在 from = 终态不可答。返回 0=已关闭/不存在（调用方 409/404）。
     */
    @Update("UPDATE feedback_questions SET answer = #{answer}, is_public = #{isPublic}, "
            + "answered_by = #{answererId}, answered_at = NOW() "
            + "WHERE id = #{id} AND deleted = 0 AND status IN ('OPEN','ANSWERED')")
    int answerIfOpen(@Param("id") Long id, @Param("answer") String answer,
                     @Param("isPublic") boolean isPublic, @Param("answererId") Long answererId);

    /** 关闭抢态：OPEN/ANSWERED→CLOSED（终态）。返回 0=已关闭。 */
    @Update("UPDATE feedback_questions SET status = 'CLOSED' "
            + "WHERE id = #{id} AND deleted = 0 AND status IN ('OPEN','ANSWERED')")
    int closeIfNotClosed(@Param("id") Long id);

    // ==================== FAQ 公开检索（脱敏：不 SELECT username/user_id） ====================

    /** FAQ 总数：is_public=true；kw 非空时标题+内容 LIKE 前缀（百级数据量无压力，量上万再评估 pg_trgm）。 */
    @org.apache.ibatis.annotations.Select("<script>SELECT COUNT(*) FROM feedback_questions "
            + "WHERE deleted = 0 AND is_public = TRUE "
            + "<if test='kw != null and kw != \"\"'> AND (title LIKE CONCAT(#{kw}, '%') ESCAPE '\\' "
            + "OR content LIKE CONCAT(#{kw}, '%') ESCAPE '\\')</if></script>")
    long countFaq(@Param("kw") String kw);

    /** FAQ 分页（按回答时间倒序）。列清单刻意无 username/user_id——公开视图字段不存在层脱敏。 */
    @org.apache.ibatis.annotations.Select("<script>SELECT id, title, content, answer, answered_at AS answeredAt "
            + "FROM feedback_questions WHERE deleted = 0 AND is_public = TRUE "
            + "<if test='kw != null and kw != \"\"'> AND (title LIKE CONCAT(#{kw}, '%') ESCAPE '\\' "
            + "OR content LIKE CONCAT(#{kw}, '%') ESCAPE '\\')</if> "
            + "ORDER BY answered_at DESC LIMIT #{size} OFFSET #{offset}</script>")
    java.util.List<com.superprogrammer.feedback.dto.FaqVO> pageFaq(@Param("kw") String kw,
                                                                   @Param("offset") long offset,
                                                                   @Param("size") long size);
}
