package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryNotification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 跨用户波及通知 mapper（V47 计划12）。BaseMapper 足够——查询走 MP wrapper。
 */
@Mapper
public interface MemoryNotificationMapper extends BaseMapper<MemoryNotification> {
}
