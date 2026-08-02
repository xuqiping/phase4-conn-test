package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeNodeMapper extends BaseMapper<KnowledgeNode> {

    /** KB 下未软删 ACTIVE 节点数（ReconciliationJob.total_nodes）。 */
    @Select("""
            SELECT COUNT(0) FROM knowledge_nodes
             WHERE kb_id = #{kbId}
               AND deleted = 0
               AND status = 'ACTIVE'
            """)
    Long countActiveByKb(@Param("kbId") Long kbId);
}
