package com.superprogrammer.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.asset.entity.AssetRoleLink;
import org.apache.ibatis.annotations.Mapper;

/** 资产↔叙事角色关联 Mapper（纯 BaseMapper）。 */
@Mapper
public interface AssetRoleLinkMapper extends BaseMapper<AssetRoleLink> {
}
