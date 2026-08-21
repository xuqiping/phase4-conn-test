package com.superprogrammer.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.feedback.entity.FeedbackNotificationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 反馈站内通知 Mapper。未读 count 走 idx_feedback_notify_unread 部分索引（铃铛 3s 轮询不压库）。
 */
@Mapper
public interface FeedbackNotificationMapper extends BaseMapper<FeedbackNotificationEntity> {

    @Select("SELECT COUNT(*) FROM feedback_notifications WHERE user_id = #{userId} AND read_at IS NULL AND deleted = 0")
    long countUnread(@Param("userId") Long userId);

    /** 标记已读（幂等：重复调返 0 正常）；仅限本人行。 */
    @Update("UPDATE feedback_notifications SET read_at = NOW() "
            + "WHERE id = #{id} AND user_id = #{userId} AND read_at IS NULL AND deleted = 0")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    /** 全部已读（铃铛「全部已读」入口）。 */
    @Update("UPDATE feedback_notifications SET read_at = NOW() "
            + "WHERE user_id = #{userId} AND read_at IS NULL AND deleted = 0")
    int markAllRead(@Param("userId") Long userId);
}
