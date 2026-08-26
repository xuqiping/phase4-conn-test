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

    /**
     * 选人候选搜索：启用用户的 id/username/name/remark，调用方传排除集合。
     * 修复III E3（12x#4）：keyword 三字段模糊（username/name/remark）——按备注「A 班」筛全班；
     * LIKE 转义由调用方预处理（同 UserController.escapeLike 口径），ESCAPE '\' 防 %/_ 语义攻击。
     */
    @Select({"<script>",
            "SELECT id, username, name, remark FROM users WHERE deleted = 0 AND status = 'ACTIVE' ",
            "<if test='keyword != null and keyword != &quot;&quot;'>",
            "AND (username LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\' ",
            "  OR name LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\' ",
            "  OR remark LIKE CONCAT('%', #{keyword}, '%') ESCAPE '\\') ",
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

    /** 11x 加固 P1-C3：统计仍 ACTIVE 且持指定权限的用户数（防最后超管锁死）。 */
    @Select("SELECT COUNT(DISTINCT u.id) FROM users u " +
            "JOIN user_roles ur ON ur.user_id = u.id " +
            "JOIN role_permissions rp ON rp.role_id = ur.role_id " +
            "JOIN permissions p ON p.id = rp.permission_id " +
            "WHERE u.deleted = 0 AND u.status = 'ACTIVE' AND p.code = #{permissionCode}")
    long countActiveByPermission(@Param("permissionCode") String permissionCode);
}
