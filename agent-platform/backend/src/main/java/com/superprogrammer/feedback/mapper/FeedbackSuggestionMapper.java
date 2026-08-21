package com.superprogrammer.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.feedback.entity.FeedbackSuggestionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 建议台 Mapper。审核翻转走条件 UPDATE 抢态（0 行=已被其他 admin 处理/状态不允许）。
 */
@Mapper
public interface FeedbackSuggestionMapper extends BaseMapper<FeedbackSuggestionEntity> {

    /**
     * 审核抢态：仅当当前状态在 fromStatuses 内才翻转（PENDING→任意结论；ADOPTED↔REJECTED 改判；
     * CLOSED 终态不在任何 fromStatuses 内 = 不可改）。返回 0=抢态失败（调用方 409）。
     */
    @Update("<script>UPDATE feedback_suggestions SET status = #{toStatus}, reply = #{reply}, "
            + "reviewed_by = #{reviewerId}, reviewed_at = NOW() "
            + "WHERE id = #{id} AND deleted = 0 AND status IN "
            + "<foreach collection='fromStatuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>"
            + "</script>")
    int reviewIfStatusIn(@Param("id") Long id, @Param("toStatus") String toStatus,
                         @Param("reply") String reply, @Param("reviewerId") Long reviewerId,
                         @Param("fromStatuses") java.util.List<String> fromStatuses);
}
