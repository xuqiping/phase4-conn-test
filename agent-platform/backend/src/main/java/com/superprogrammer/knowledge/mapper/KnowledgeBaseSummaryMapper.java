package com.superprogrammer.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.knowledge.entity.KnowledgeBaseSummary;
import org.apache.ibatis.annotations.Mapper;

/** C7 库级摘要（V172）。摘要读取仅 Service 内部（注入 prompt），无对外 VO。 */
@Mapper
public interface KnowledgeBaseSummaryMapper extends BaseMapper<KnowledgeBaseSummary> {
}
