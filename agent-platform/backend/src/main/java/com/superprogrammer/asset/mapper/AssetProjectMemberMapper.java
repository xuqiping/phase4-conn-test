package com.superprogrammer.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.asset.entity.AssetProjectMember;
import org.apache.ibatis.annotations.Mapper;

/** 项目成员授权 Mapper（纯 BaseMapper）。 */
@Mapper
public interface AssetProjectMemberMapper extends BaseMapper<AssetProjectMember> {
}
