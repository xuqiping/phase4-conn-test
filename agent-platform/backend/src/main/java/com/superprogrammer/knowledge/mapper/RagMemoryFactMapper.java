package com.superprogrammer.knowledge.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * rag_memory_facts decay 兜底（阶段7 ReconciliationJob，v6 §8.9/§8.9a）。
 *
 * <p>镜像 {@link RagAnswerCacheMapper#deleteDecayed}：批量硬删 decay 过期的 ACTIVE 行。
 * 当前无生产者写入该表（M2 软提示特性未启用），本接口仅就位供 sibling purge 闭环，
 * 保证将来启用 M2 时无需再补对账路径。
 *
 * <p>halfvec key_embedding 列本接口不读写（仅按 decay_at/status 扫删）。
 */
@Mapper
public interface RagMemoryFactMapper {

    /**
     * 批量硬删 decay 过期的 ACTIVE 行（缓存/软提示非权威数据，硬删避免 HNSW 索引带 ARCHIVED 行拖慢每次查）。
     * 返回实际删除数。
     */
    @Delete("""
            DELETE FROM rag_memory_facts
             WHERE id IN (
                 SELECT id FROM rag_memory_facts
                  WHERE status = 'ACTIVE'
                    AND decay_at IS NOT NULL
                    AND decay_at < now()
                  LIMIT #{batch}
             )
            """)
    int deleteDecayed(@Param("batch") int batch);

    /** decay 过期 ACTIVE 行计数（报告/观测，不删）。 */
    @Select("""
            SELECT COUNT(0) FROM rag_memory_facts
             WHERE status = 'ACTIVE'
               AND decay_at IS NOT NULL
               AND decay_at < now()
            """)
    Long countDecayed();
}
