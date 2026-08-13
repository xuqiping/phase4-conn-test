package com.superprogrammer.knowledge.opensearch;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeIndexSnapshotMapper {

    @Insert("""
            INSERT INTO rag_index_snapshots(tenant_id, kb_id, snapshot_id, physical_index, status)
            VALUES (1, #{kbId}, #{snapshotId}, #{physicalIndex}, 'BUILDING')
            ON CONFLICT (tenant_id, kb_id, snapshot_id) DO UPDATE SET
                physical_index=EXCLUDED.physical_index, status='BUILDING'
            """)
    void begin(@Param("kbId") long kbId, @Param("snapshotId") String snapshotId,
               @Param("physicalIndex") String physicalIndex);

    @org.apache.ibatis.annotations.Update("""
            UPDATE rag_index_snapshots SET status=#{status}
             WHERE tenant_id=1 AND kb_id=#{kbId} AND snapshot_id=#{snapshotId}
            """)
    void updateStatus(@Param("kbId") long kbId, @Param("snapshotId") String snapshotId,
                      @Param("status") String status);

    @Select("""
            SELECT snapshot_id FROM rag_index_snapshots
             WHERE tenant_id=1 AND kb_id=#{kbId}
             ORDER BY created_at DESC LIMIT 1
            """)
    String latestSnapshot(@Param("kbId") long kbId);

    @Insert("""
            INSERT INTO rag_index_snapshots(tenant_id, kb_id, snapshot_id, physical_index, status)
            VALUES (1, #{kbId}, #{snapshotId}, #{physicalIndex}, 'REGISTERED')
            ON CONFLICT (tenant_id, kb_id, snapshot_id) DO UPDATE SET
                physical_index=EXCLUDED.physical_index,
                status='REGISTERED'
            """)
    void register(@Param("kbId") long kbId, @Param("snapshotId") String snapshotId,
                  @Param("physicalIndex") String physicalIndex);

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM rag_index_snapshots
                 WHERE tenant_id=1 AND kb_id=#{kbId} AND snapshot_id=#{snapshotId}
                   AND status IN ('REGISTERED','READY')
            )
            """)
    boolean registered(@Param("kbId") long kbId, @Param("snapshotId") String snapshotId);

    @Select("""
            SELECT physical_index FROM rag_index_snapshots
             WHERE tenant_id=1 AND kb_id=#{kbId} AND snapshot_id=#{snapshotId}
            """)
    String physicalIndex(@Param("kbId") long kbId, @Param("snapshotId") String snapshotId);

    @Select("""
            SELECT kb_id AS knowledgeBaseId,
                   active_snapshot_id AS activeSnapshotId,
                   previous_snapshot_id AS previousSnapshotId,
                   config_version AS configVersion,
                   updated_by AS updatedBy
              FROM rag_index_routes
             WHERE tenant_id=1 AND kb_id=#{kbId}
            """)
    KnowledgeIndexOperationsService.SnapshotRecord load(@Param("kbId") long kbId);

    @Insert("""
            INSERT INTO rag_index_routes
                (tenant_id, kb_id, active_snapshot_id, previous_snapshot_id,
                 config_version, updated_by, updated_at)
            VALUES
                (1, #{knowledgeBaseId}, #{activeSnapshotId}, #{previousSnapshotId},
                 #{configVersion}, #{updatedBy}, NOW())
            ON CONFLICT (tenant_id, kb_id) DO UPDATE SET
                active_snapshot_id=EXCLUDED.active_snapshot_id,
                previous_snapshot_id=EXCLUDED.previous_snapshot_id,
                config_version=EXCLUDED.config_version,
                updated_by=EXCLUDED.updated_by,
                updated_at=NOW()
            """)
    void save(KnowledgeIndexOperationsService.SnapshotRecord record);
}
