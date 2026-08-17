package com.superprogrammer.projectgroup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 组成员 Mapper（限额/used 行锁更新在 Step2 钱包服务走条件 UPDATE）。
 */
@Mapper
public interface ProjectGroupMemberMapper extends BaseMapper<ProjectGroupMemberEntity> {
}
