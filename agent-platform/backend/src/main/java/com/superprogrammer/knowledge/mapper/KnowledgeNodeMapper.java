package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.KnowledgeNode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeNodeMapper extends BaseMapper<KnowledgeNode> {
}
