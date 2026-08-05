package com.superprogrammer.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.asset.entity.AssetProject;
import org.apache.ibatis.annotations.Mapper;

/** 项目资产库·项目 Mapper（纯 BaseMapper，查询在 service 用 LambdaQueryWrapper）。 */
@Mapper
public interface AssetProjectMapper extends BaseMapper<AssetProject> {
}
