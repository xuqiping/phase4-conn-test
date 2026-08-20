package com.superprogrammer.projectgroup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.projectgroup.entity.ProjectGroupJoinRequestEntity;
import org.apache.ibatis.annotations.Mapper;

/** 公共池入组申请 Mapper（V138）。状态翻转走服务层条件 UPDATE，无需自定义 SQL。 */
@Mapper
public interface ProjectGroupJoinRequestMapper extends BaseMapper<ProjectGroupJoinRequestEntity> {
}
