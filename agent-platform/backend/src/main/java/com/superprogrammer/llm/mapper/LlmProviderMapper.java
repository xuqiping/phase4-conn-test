package com.superprogrammer.llm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LlmProviderMapper extends BaseMapper<LlmProviderEntity> {

    /**
     * 创建价表事务内锁定供应商，串行化同供应商的重复检查。
     * <p>注意：llm_providers.deleted 列为 INTEGER（见 V6__create_memories_and_providers.sql），
     * PG 无 integer→boolean 隐式转换，写 {@code deleted = false} 会抛
     * {@code operator does not exist: integer = boolean} → BadSqlGrammarException → 兜底 500。
     * 必须用 {@code = 0}（与 V8 user_llm_providers 索引约定一致）。
     */
    @Select("SELECT * FROM llm_providers WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    LlmProviderEntity selectByIdForUpdate(@Param("id") Long id);
}
