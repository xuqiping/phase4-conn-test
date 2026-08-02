// agent-platform/backend/src/main/java/com/superprogrammer/execution/mapper/ExecutionLogMapper.java
package com.superprogrammer.execution.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.execution.entity.ExecutionLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExecutionLogMapper extends BaseMapper<ExecutionLog> {
}
