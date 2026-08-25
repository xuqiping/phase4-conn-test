package com.superprogrammer.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.feedback.entity.FeedbackMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * feedback_messages Mapper（V154）。线程读取走 idx_feedback_message_target 部分索引。
 */
@Mapper
public interface FeedbackMessageMapper extends BaseMapper<FeedbackMessageEntity> {
}
