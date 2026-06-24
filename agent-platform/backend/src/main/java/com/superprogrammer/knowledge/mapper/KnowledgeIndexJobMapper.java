package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.KnowledgeIndexJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeIndexJobMapper extends BaseMapper<KnowledgeIndexJob> {

    /**
     * 文档下仍未完成的 job 数（PENDING/RUNNING），用于判断整文档是否可置 INDEXED。
     * JOIN knowledge_nodes 取 document_id（job 表无 doc 维度）。
     */
    @Select("""
            SELECT COUNT(0) FROM knowledge_index_jobs j
            JOIN knowledge_nodes n ON j.node_id = n.id
            WHERE n.document_id = #{docId}
              AND j.status IN ('PENDING', 'RUNNING')
            """)
    Long countPendingRunningByDoc(@Param("docId") Long docId);
}
