package com.superprogrammer.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.asset.entity.AssetBinding;
import org.apache.ibatis.annotations.Mapper;

/** 资产↔画布绑定 Mapper（纯 BaseMapper）。 */
@Mapper
public interface AssetBindingMapper extends BaseMapper<AssetBinding> {
}
