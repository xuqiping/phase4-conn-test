package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 记忆项目成员 mapper（V47 计划12）。BaseMapper 足够——查询走 MP wrapper。
 * 独立于 Agent 模块 project_members（旧表不动）。
 */
@Mapper
public interface MemoryProjectMemberMapper extends BaseMapper<MemoryProjectMember> {
}
