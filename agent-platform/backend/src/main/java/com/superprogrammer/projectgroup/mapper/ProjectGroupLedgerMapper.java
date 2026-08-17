package com.superprogrammer.projectgroup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.projectgroup.entity.ProjectGroupLedgerEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 组流水 Mapper（append-only，写走 BaseMapper.insert；查询在 service 用 LambdaQueryWrapper）。
 */
@Mapper
public interface ProjectGroupLedgerMapper extends BaseMapper<ProjectGroupLedgerEntity> {
}
