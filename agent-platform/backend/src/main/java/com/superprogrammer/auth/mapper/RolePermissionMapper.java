// agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/RolePermissionMapper.java
package com.superprogrammer.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.auth.entity.RolePermission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {
}
