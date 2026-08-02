package com.superprogrammer.llm.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * embedding_model_versions 注册表只读访问（RAG 向量表 model_code→dim/table 注册键）。
 * Phase1 单行 doubao/2048。仅用于 provider 维度只读展示，不映射完整实体（表无 deleted/version）。
 */
@Mapper
public interface EmbeddingModelVersionMapper {

    /** 当前 ACTIVE 向量模型维度（Phase1 单行；多模型路由=Phase2）。无则 null。 */
    @Select("SELECT dim FROM embedding_model_versions WHERE status = 'ACTIVE' ORDER BY id LIMIT 1")
    Integer findActiveDimension();
}
