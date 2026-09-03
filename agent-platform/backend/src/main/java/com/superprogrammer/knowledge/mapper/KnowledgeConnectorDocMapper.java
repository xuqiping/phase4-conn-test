package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.KnowledgeConnectorDoc;
import org.apache.ibatis.annotations.Mapper;

/** knowledge_connector_docs 增量账本读写（WP6 Step1，V175）。etag 对比/认领查询 Step3 扩。 */
@Mapper
public interface KnowledgeConnectorDocMapper extends BaseMapper<KnowledgeConnectorDoc> {
}
