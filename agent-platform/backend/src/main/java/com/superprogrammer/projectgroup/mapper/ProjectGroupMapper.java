package com.superprogrammer.projectgroup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目组 Mapper（CRUD 走 BaseMapper；组长校验/软删前置在 service）。
 */
@Mapper
public interface ProjectGroupMapper extends BaseMapper<ProjectGroupEntity> {
}
