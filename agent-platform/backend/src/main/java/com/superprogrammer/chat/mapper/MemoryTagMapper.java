package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 记忆标签 mapper（V47 计划12）。
 * 写时归一用：findByUserSubjectTopic 精确命中（UNIQUE(user_id,subject,topic) 兜底）。
 * findNearestByAnchor：anchor 半向量召回（D 迭代用，此处提前建好证 XML halfvec 转义启动期无异常）。
 */
@Mapper
public interface MemoryTagMapper extends BaseMapper<MemoryTag> {

    /** 精确 (user, subject, topic) 命中——写时归一首查路径。 */
    MemoryTag findByUserSubjectTopic(@Param("userId") Long userId,
                                     @Param("subject") String subject,
                                     @Param("topic") String topic);

    /** anchor 半向量近邻（粗筛主通道，D 迭代召回用）。queryVec 为 halfvec 文本 '[..]'。 */
    List<MemoryTag> findNearestByAnchor(@Param("userId") Long userId,
                                        @Param("queryVec") String queryVec,
                                        @Param("limit") int limit);
}
