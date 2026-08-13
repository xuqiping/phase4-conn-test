package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
    @Select("SELECT * FROM knowledge_documents WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    KnowledgeDocument selectByIdForUpdate(@Param("id") Long id);

    @Update("""
            UPDATE knowledge_documents
               SET current_version_id = #{newVersionId}, updated_by = #{operatorId},
                   updated_at = now(), version = version + 1
             WHERE id = #{documentId} AND deleted = 0
               AND current_version_id IS NOT DISTINCT FROM #{expectedVersionId}
            """)
    int moveCurrentVersion(@Param("documentId") Long documentId,
                           @Param("newVersionId") Long newVersionId,
                           @Param("expectedVersionId") Long expectedVersionId,
                           @Param("operatorId") Long operatorId);

    /** 治理字段允许显式清空；tags 是 jsonb，不能依赖通用 updateById 的非空策略与 JDBC 类型推断。 */
    @Update("""
            UPDATE knowledge_documents
               SET owner_id = #{doc.ownerId}, source_type = #{doc.sourceType}, source_uri = #{doc.sourceUri},
                   source_updated_at = #{doc.sourceUpdatedAt}, authority_level = #{doc.authorityLevel},
                   confidentiality_level = #{doc.confidentialityLevel}, tags = #{doc.tags}::jsonb,
                   effective_at = #{doc.effectiveAt}, expired_at = #{doc.expiredAt},
                   updated_by = #{doc.updatedBy}, updated_at = now(), version = version + 1
             WHERE id = #{doc.id} AND deleted = 0
            """)
    int updateGovernance(@Param("doc") KnowledgeDocument document);
}
