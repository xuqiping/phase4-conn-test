// agent-platform/backend/src/main/java/com/superprogrammer/workflow/mapper/WorkflowNodeMapper.java
package com.superprogrammer.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.workflow.entity.WorkflowNode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkflowNodeMapper extends BaseMapper<WorkflowNode> {
    @Delete("DELETE FROM workflow_nodes WHERE workflow_id = #{workflowId}")
    int deletePhysicallyByWorkflowId(@Param("workflowId") Long workflowId);
}
