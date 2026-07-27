package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryTurn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 记忆流水账 mapper（V47 计划12）。
 * 读方法内联 SCOPE_FILTER（见 xml）——自己 ∪ accessible 项目集（向量 1/2）。
 * updateProjectIds 显式 typeHandler——绕开 LambdaUpdateWrapper 不读 typeHandler 的坑（V33 教训）。
 */
@Mapper
public interface MemoryTurnMapper extends BaseMapper<MemoryTurn> {

    /** 当前用户可见的流水账（自己的 + accessible 项目集内的）。 */
    List<MemoryTurn> findVisibleTurns(@Param("userId") Long userId,
                                      @Param("accessibleProjectIds") List<Long> accessibleProjectIds);

    /** 挂载项目——显式 typeHandler，供 L11 多挂/卸用。 */
    int updateProjectIds(@Param("id") Long id, @Param("projectIds") List<Long> projectIds);
}
