// agent-platform/backend/src/main/java/com/superprogrammer/agent/mapper/SkillMapper.java
package com.superprogrammer.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.agent.entity.Skill;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkillMapper extends BaseMapper<Skill> {
}
