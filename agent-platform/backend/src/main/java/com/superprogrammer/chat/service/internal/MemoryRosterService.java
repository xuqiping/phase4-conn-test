package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.dto.MemoryRosterVO;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 计划12 · I2 · 项目花名册 service（总体设计 §3.6/§3.7）。
 * <p>
 * 返项目全部成员（含 DEPARTED 已离开，保交接），带 username/name 供前端配 ACL 授权矩阵 + 召回人员多选。
 * 读权：项目 ACTIVE 成员可见自己项目花名册（{@link #isMember} controller 判，非成员/DEPARTED → 403）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRosterService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final MemoryProjectMemberMapper memberMapper;

    /** 项目花名册（含 DEPARTED + departed_at，配 ACL 矩阵/召回人员多选源数据）。 */
    public List<MemoryRosterVO> getRoster(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        return memberMapper.findRoster(projectId);
    }

    /**
     * 调用者是否为项目 ACTIVE 成员（roster 端点可见性判据）。
     * <p>
     * DEPARTED 已离开 → false（无项目读权）。任意 role（OWNER/ADMIN/MEMBER）均算成员。
     */
    public boolean isMember(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            return false;
        }
        MemoryProjectMember m = memberMapper.selectOne(new LambdaQueryWrapper<MemoryProjectMember>()
                .eq(MemoryProjectMember::getProjectId, projectId)
                .eq(MemoryProjectMember::getUserId, userId));
        return m != null && STATUS_ACTIVE.equals(m.getStatus());
    }
}
