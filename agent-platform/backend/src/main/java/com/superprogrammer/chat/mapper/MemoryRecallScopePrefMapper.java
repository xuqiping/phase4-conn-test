package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryRecallScopePref;
import org.apache.ibatis.annotations.Mapper;

/**
 * 计划12 · D-7 · 召回 scope 用户偏好 mapper。
 * <p>
 * 1:1 用户偏好查询走 {@link BaseMapper#selectOne}（{@code user_id} 条件）；
 * upsert 由 service 层 selectOne + insert/updateById 实现（不依赖 DB ON CONFLICT，跨库一致）。
 */
@Mapper
public interface MemoryRecallScopePrefMapper extends BaseMapper<MemoryRecallScopePref> {
}
