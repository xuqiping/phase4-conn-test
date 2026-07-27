package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryProjectSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 记忆项目级 gen 开关 mapper（V47 计划12，owner 维度）。
 * BaseMapper 足够——查询走 MP wrapper（UNIQUE(project_id) 单行）。
 */
@Mapper
public interface MemoryProjectSettingMapper extends BaseMapper<MemoryProjectSetting> {
}
