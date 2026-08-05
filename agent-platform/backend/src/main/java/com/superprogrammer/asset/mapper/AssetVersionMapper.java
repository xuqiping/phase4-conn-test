package com.superprogrammer.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.asset.entity.AssetVersion;
import org.apache.ibatis.annotations.Mapper;

/** 资产版本快照 Mapper（纯 BaseMapper）。 */
@Mapper
public interface AssetVersionMapper extends BaseMapper<AssetVersion> {
}
