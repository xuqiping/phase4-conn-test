package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.KnowledgeDocumentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KnowledgeDocumentVersionMapper extends BaseMapper<KnowledgeDocumentVersion> {

    @Select("SELECT COALESCE(MAX(version_no), 0) + 1 FROM knowledge_document_versions WHERE document_id = #{documentId}")
    Integer nextVersionNo(@Param("documentId") Long documentId);

    @Select("SELECT * FROM knowledge_document_versions WHERE document_id = #{documentId} ORDER BY version_no DESC")
    List<KnowledgeDocumentVersion> listByDocument(@Param("documentId") Long documentId);

    @Update("""
            UPDATE knowledge_document_versions
               SET status = CASE WHEN status = 'EFFECTIVE' THEN 'SUPERSEDED' ELSE status END,
                   replaced_by_version_id = CASE WHEN status = 'EFFECTIVE' THEN #{replacementId} ELSE replaced_by_version_id END
             WHERE document_id = #{documentId} AND id <> #{replacementId} AND status = 'EFFECTIVE'
            """)
    int archiveEffective(@Param("documentId") Long documentId, @Param("replacementId") Long replacementId);

    @Update("""
            UPDATE knowledge_document_versions
               SET status = 'EFFECTIVE', effective_at = now()
             WHERE id = #{versionId} AND status IN ('DRAFT', 'ARCHIVED')
            """)
    int markEffective(@Param("versionId") Long versionId, @Param("operatorId") Long operatorId);

    @Update("""
            UPDATE knowledge_document_versions
               SET status = 'REVOKED', revoked_at = now(), revoked_by = #{operatorId}
             WHERE id = #{versionId} AND status <> 'REVOKED'
            """)
    int revoke(@Param("versionId") Long versionId, @Param("operatorId") Long operatorId);

    @Update("""
            UPDATE knowledge_document_versions
               SET parser_version = #{parserVersion},
                   parse_artifact_ref = #{artifactRef},
                   parse_artifact_hash = #{artifactHash},
                   parsed_at = now()
             WHERE id = #{versionId}
            """)
    int updateParseArtifact(@Param("versionId") Long versionId,
                            @Param("parserVersion") String parserVersion,
                            @Param("artifactRef") String artifactRef,
                            @Param("artifactHash") String artifactHash);
}
