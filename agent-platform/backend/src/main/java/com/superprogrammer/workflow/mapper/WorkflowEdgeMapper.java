// agent-platform/backend/src/main/java/com/superprogrammer/workflow/mapper/WorkflowEdgeMapper.java
package com.superprogrammer.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.workflow.entity.WorkflowEdge;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkflowEdgeMapper extends BaseMapper<WorkflowEdge> {
    @Delete("DELETE FROM workflow_edges WHERE workflow_id = #{workflowId}")
    int deletePhysicallyByWorkflowId(@Param("workflowId") Long workflowId);
}
