package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /** 未软删的 KB id 列表（按 id 升序，分页），供 ReconciliationJob 逐 KB 对账扫描。 */
    @Select("""
            SELECT id FROM knowledge_bases
             WHERE deleted = 0
             ORDER BY id
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<Long> listActiveKbIds(@Param("limit") int limit, @Param("offset") int offset);
}
