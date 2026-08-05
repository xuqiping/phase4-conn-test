package com.superprogrammer.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.asset.entity.Asset;
import org.apache.ibatis.annotations.Mapper;

/** 项目资产库·资产 Mapper（纯 BaseMapper）。 */
@Mapper
public interface AssetMapper extends BaseMapper<Asset> {
}
