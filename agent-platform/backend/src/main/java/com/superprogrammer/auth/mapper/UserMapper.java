// agent-platform/backend/src/main/java/com/superprogrammer/auth/mapper/UserMapper.java
package com.superprogrammer.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 资产域邀请候选：只查启用用户的 id/username，并由调用方传入排除集合。 */
    @Select({"<script>",
            "SELECT id, username FROM users WHERE deleted = 0 AND status = 'ACTIVE' ",
            "<if test='keyword != null and keyword != &quot;&quot;'>",
            "AND username LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\' ",
            "</if>",
            "<if test='excludedIds != null and !excludedIds.isEmpty()'>",
            "AND id NOT IN ",
            "<foreach collection='excludedIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</if>",
            "ORDER BY username ASC, id ASC LIMIT #{limit}",
            "</script>"})
    List<User> searchActiveCandidates(@Param("keyword") String keyword,
                                      @Param("excludedIds") List<Long> excludedIds,
                                      @Param("limit") int limit);

    /**
     * 根据用户名查询用户及其角色编码列表
     */
    List<String> selectRoleCodesByUsername(String username);

    /**
     * 根据用户ID查询权限编码列表
     */
    List<String> selectPermissionCodesByUserId(Long userId);
}
