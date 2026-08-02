// agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/UserMapper.java
package com.superprogrammer.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据用户名查询用户及其角色编码列表
     */
    List<String> selectRoleCodesByUsername(String username);

    /**
     * 根据用户ID查询权限编码列表
     */
    List<String> selectPermissionCodesByUserId(Long userId);
}
