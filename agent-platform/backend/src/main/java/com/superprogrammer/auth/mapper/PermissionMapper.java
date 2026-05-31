// agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/PermissionMapper.java
package com.superprogrammer.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.auth.entity.Permission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {
}
