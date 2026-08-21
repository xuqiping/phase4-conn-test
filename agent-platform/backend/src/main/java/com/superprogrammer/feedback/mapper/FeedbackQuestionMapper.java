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
}
