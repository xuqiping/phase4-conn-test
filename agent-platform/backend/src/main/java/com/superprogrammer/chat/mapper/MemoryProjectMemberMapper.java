package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.dto.MemoryRosterVO;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 记忆项目成员 mapper（V47 计划12）。BaseMapper 足够——查询走 MP wrapper。
 * 独立于 Agent 模块 project_members（旧表不动）。
 * <p>
 * I2 加 {@link #findRoster}（join users 取 username/name，含 DEPARTED，配 ACL 矩阵 + 召回人员多选）。
 */
@Mapper
public interface MemoryProjectMemberMapper extends BaseMapper<MemoryProjectMember> {

    /**
     * 项目花名册（总体设计 §3.6/§3.7）：全部成员含 DEPARTED（保交接），带 username/name 供前端展示。
     * <p>
     * ACL 配权/召回人员多选的源数据。{@code users.name} 可空（前端回退 username）。
     */
    List<MemoryRosterVO> findRoster(@Param("projectId") Long projectId);

    /**
     * 计划12 · F · gen 矩阵：当前用户所在的 ACTIVE 项目，join 项目名 + owner 项目级开关 +
     * 本人会员覆写开关（null = 无行 = 默认开，由 service 解析）。DEPARTED 不列（无可配权）。
     */
    List<com.superprogrammer.chat.dto.MemoryGenMatrixItemVO> findMyGenMatrix(@Param("userId") Long userId);
}
