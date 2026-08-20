package com.superprogrammer.projectgroup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.projectgroup.entity.ProjectGroupInviteEntity;
import org.apache.ibatis.annotations.Mapper;

/** 组邀请 Mapper（V138）。状态翻转走服务层条件 UPDATE（LambdaUpdateWrapper），无需自定义 SQL。 */
@Mapper
public interface ProjectGroupInviteMapper extends BaseMapper<ProjectGroupInviteEntity> {
}
