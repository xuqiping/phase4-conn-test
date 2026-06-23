package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.KnowledgeReconciliationReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * knowledge_reconciliation_reports 读写（阶段7 ReconciliationJob）。
 * 无 halfvec 列 → 走 BaseMapper.insert 即可（自定义 @Select 仅 listRecentByKb 供未来管理 UI）。
 */
@Mapper
public interface KnowledgeReconciliationReportMapper extends BaseMapper<KnowledgeReconciliationReport> {

    /** 某 KB 最近 N 条对账报告（按 scanned_at 倒序），供管理 UI 观察趋势。 */
    @Select("""
            SELECT * FROM knowledge_reconciliation_reports
             WHERE kb_id = #{kbId}
             ORDER BY scanned_at DESC
             LIMIT #{limit}
            """)
    List<KnowledgeReconciliationReport> listRecentByKb(@Param("kbId") Long kbId,
                                                       @Param("limit") int limit);
}
