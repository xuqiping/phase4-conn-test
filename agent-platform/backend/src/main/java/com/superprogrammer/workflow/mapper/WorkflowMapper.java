// agent-platform/backend/src/main/java/com/superprogrammer/workflow/mapper/WorkflowMapper.java
package com.superprogrammer.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.workflow.entity.Workflow;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkflowMapper extends BaseMapper<Workflow> {
}
