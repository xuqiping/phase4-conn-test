package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryProjectUserSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 记忆会员个人 gen 覆写开关 mapper（V47 计划12）。
 * BaseMapper 足够——查询走 MP wrapper（UNIQUE(project_id,user_id) 单行）。
 */
@Mapper
public interface MemoryProjectUserSettingMapper extends BaseMapper<MemoryProjectUserSetting> {
}
