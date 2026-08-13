package com.superprogrammer.knowledge.evaluation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EvaluationMapper {
    @Insert("""
            INSERT INTO rag_eval_datasets(tenant_id,kb_id,name,description,created_by)
            VALUES(#{tenantId},#{kbId},#{name},#{description},#{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertDataset(DatasetRow row);

    @Select("""
            SELECT id,tenant_id,kb_id,name,description,created_by
            FROM rag_eval_datasets WHERE tenant_id=#{tenantId} AND id=#{datasetId}
            """)
    DatasetRow findDataset(@Param("tenantId") long tenantId, @Param("datasetId") long datasetId);

    @Insert("""
            INSERT INTO rag_eval_cases(dataset_id,query_type,question,expected_chunk_ids,
              forbidden_chunk_ids,answerable,metadata)
            VALUES(#{datasetId},#{queryType},#{question},#{expectedChunkIds}::jsonb,
              #{forbiddenChunkIds}::jsonb,#{answerable},#{metadata}::jsonb)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertCase(CaseRow row);

    @Select("""
            SELECT c.id,c.dataset_id,c.query_type,c.question,c.expected_chunk_ids::text,
                   c.forbidden_chunk_ids::text,c.answerable,c.metadata::text
            FROM rag_eval_cases c JOIN rag_eval_datasets d ON d.id=c.dataset_id
            WHERE d.tenant_id=#{tenantId} AND c.dataset_id=#{datasetId} ORDER BY c.id
            """)
    List<CaseRow> listCases(@Param("tenantId") long tenantId, @Param("datasetId") long datasetId);

    class DatasetRow {
        public Long id; public Long tenantId; public Long kbId; public String name;
        public String description; public Long createdBy;
    }
    class CaseRow {
        public Long id; public Long datasetId; public String queryType; public String question;
        public String expectedChunkIds; public String forbiddenChunkIds; public Boolean answerable;
        public String metadata;
    }
}
