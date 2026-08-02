// agent-platform/backend/src/main/java/com/superprogrammer/agent/mapper/AgentMapper.java
package com.superprogrammer.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.agent.entity.Agent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentMapper extends BaseMapper<Agent> {
}
