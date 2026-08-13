package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.RagRankingConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RagRankingConfigMapper extends BaseMapper<RagRankingConfig> {

    @Select("""
            SELECT * FROM rag_ranking_configs
             WHERE tenant_id = 1 AND kb_id = #{kbId}
               AND status = 'ACTIVE' AND deleted = 0
             LIMIT 1
            """)
    RagRankingConfig findActiveForKb(Long kbId);

    @Select("""
            SELECT * FROM rag_ranking_configs
             WHERE tenant_id = 1 AND kb_id IS NULL
               AND status = 'ACTIVE' AND deleted = 0
             LIMIT 1
            """)
    RagRankingConfig findActiveDefault();

    @Select("""
            SELECT * FROM rag_ranking_configs
             WHERE tenant_id = 1 AND kb_id = #{kbId}
               AND config_version = #{configVersion} AND deleted = 0
             LIMIT 1
            """)
    RagRankingConfig findForKbByVersion(Long kbId, String configVersion);

    @Select("""
            SELECT * FROM rag_ranking_configs
             WHERE tenant_id = 1 AND kb_id IS NULL
               AND config_version = #{configVersion} AND deleted = 0
             LIMIT 1
            """)
    RagRankingConfig findDefaultByVersion(String configVersion);

    @Update("""
            UPDATE rag_ranking_configs
               SET status = 'ARCHIVED', updated_by = #{userId}, updated_at = now(), version = version + 1
             WHERE tenant_id = 1 AND kb_id = #{kbId} AND status = 'ACTIVE' AND deleted = 0
            """)
    int archiveActiveForKb(Long kbId, Long userId);

    @Update("""
            UPDATE rag_ranking_configs
               SET status = 'ARCHIVED', updated_by = #{userId}, updated_at = now(), version = version + 1
             WHERE tenant_id = 1 AND kb_id IS NULL AND status = 'ACTIVE' AND deleted = 0
            """)
    int archiveActiveDefault(Long userId);
}
