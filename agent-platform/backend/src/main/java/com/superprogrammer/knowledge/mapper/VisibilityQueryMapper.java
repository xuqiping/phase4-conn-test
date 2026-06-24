package com.superprogrammer.knowledge.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 可见集计算 SQL（v6 §5.1，阶段4-A）。
 * 三层主体并集：USER 直接 ∪ 其 ROLE 授权 ∪ 其 DEPARTMENT 授权；target KB/DIRECTORY/DOCUMENT 展开到 doc_id。
 *
 * ⚠️ custom SQL 绕过 @TableLogic → deleted=0 硬写（knowledge_documents）。
 * ⚠️ 空 roleIds/deptIds 用 <if size>0 包裹整段；USER 子句恒在 → SQL 恒合法。
 * ⚠️ knowledge_permissions 无 deleted（撤销=硬删），无需 deleted 过滤。
 */
@Mapper
public interface VisibilityQueryMapper {

    /**
     * KB 级 can_read 探针：任一主体层（USER/ROLE/DEPT）对 KB 有 can_read → 全量可见。
     * 返回 true → 召回省略 document_id 谓词。
     */
    @Select("""
            <script>
            SELECT EXISTS (
              SELECT 1 FROM knowledge_permissions p
              WHERE p.tenant_id = #{tenantId}
                AND p.target_type = 'KB'
                AND p.target_id   = #{kbId}
                AND p.can_read    = TRUE
                AND (
                    (p.subject_type = 'USER' AND p.subject_id = #{userId})
                    <if test="roleIds != null and roleIds.size() > 0">
                    OR (p.subject_type = 'ROLE' AND p.subject_id IN
                        <foreach collection="roleIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>)
                    </if>
                    <if test="deptIds != null and deptIds.size() > 0">
                    OR (p.subject_type = 'DEPARTMENT' AND p.subject_id IN
                        <foreach collection="deptIds" item="did" open="(" separator="," close=")">#{did}</foreach>)
                    </if>
                )
            )
            </script>
            """)
    boolean hasKbLevelRead(@Param("tenantId") Long tenantId,
                           @Param("kbId") Long kbId,
                           @Param("userId") Long userId,
                           @Param("roleIds") List<Long> roleIds,
                           @Param("deptIds") List<Long> deptIds);

    /**
     * 三层文档并集：USER/ROLE/DEPARTMENT 任一层对 DOCUMENT/DIRECTORY(d.directory_id)/KB 有 can_read → 命中。
     * DISTINCT 天然去重；scoped to kb。
     */
    @Select("""
            <script>
            SELECT DISTINCT d.id
            FROM knowledge_documents d
            WHERE d.kb_id = #{kbId}
              AND d.deleted = 0
              AND (
                  EXISTS (SELECT 1 FROM knowledge_permissions p
                          WHERE p.tenant_id = #{tenantId}
                            AND p.subject_type = 'USER' AND p.subject_id = #{userId}
                            AND p.can_read = TRUE
                            AND ( (p.target_type = 'DOCUMENT'  AND p.target_id = d.id)
                               OR (p.target_type = 'DIRECTORY' AND p.target_id = d.directory_id)
                               OR (p.target_type = 'KB'        AND p.target_id = #{kbId}) ))
                  <if test="roleIds != null and roleIds.size() > 0">
                  OR EXISTS (SELECT 1 FROM knowledge_permissions p
                             WHERE p.tenant_id = #{tenantId}
                               AND p.subject_type = 'ROLE' AND p.can_read = TRUE
                               AND p.subject_id IN
                               <foreach collection="roleIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>
                               AND ( (p.target_type = 'DOCUMENT'  AND p.target_id = d.id)
                                  OR (p.target_type = 'DIRECTORY' AND p.target_id = d.directory_id)
                                  OR (p.target_type = 'KB'        AND p.target_id = #{kbId}) ))
                  </if>
                  <if test="deptIds != null and deptIds.size() > 0">
                  OR EXISTS (SELECT 1 FROM knowledge_permissions p
                             WHERE p.tenant_id = #{tenantId}
                               AND p.subject_type = 'DEPARTMENT' AND p.can_read = TRUE
                               AND p.subject_id IN
                               <foreach collection="deptIds" item="did" open="(" separator="," close=")">#{did}</foreach>
                               AND ( (p.target_type = 'DOCUMENT'  AND p.target_id = d.id)
                                  OR (p.target_type = 'DIRECTORY' AND p.target_id = d.directory_id)
                                  OR (p.target_type = 'KB'        AND p.target_id = #{kbId}) ))
                  </if>
              )
            </script>
            """)
    List<Long> computeVisibleDocs3Layer(@Param("tenantId") Long tenantId,
                                        @Param("kbId") Long kbId,
                                        @Param("userId") Long userId,
                                        @Param("roleIds") List<Long> roleIds,
                                        @Param("deptIds") List<Long> deptIds);
}
