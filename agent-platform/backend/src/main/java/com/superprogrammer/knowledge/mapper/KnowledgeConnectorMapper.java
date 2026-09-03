package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.KnowledgeConnector;
import org.apache.ibatis.annotations.Mapper;

/** knowledge_connectors 读写（WP6 Step1，V175）。CRUD 走 BaseMapper；worker 认领查询 Step3 扩。 */
@Mapper
public interface KnowledgeConnectorMapper extends BaseMapper<KnowledgeConnector> {
}
