package com.superprogrammer.llm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LlmProviderMapper extends BaseMapper<LlmProviderEntity> {

    /** 创建价表事务内锁定供应商，串行化同供应商的重复检查。 */
    @Select("SELECT * FROM llm_providers WHERE id = #{id} AND deleted = false FOR UPDATE")
    LlmProviderEntity selectByIdForUpdate(@Param("id") Long id);
}
