package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryRosterVO;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 计划12 · I2 · 项目花名册 service（总体设计 §3.6/§3.7）。
 * <p>
 * 返项目全部成员（含 DEPARTED 已离开，保交接），带 username/name 供前端配 ACL 授权矩阵 + 召回人员多选。
 * 读权：项目成员可见自己项目花名册（controller 校验调用者为成员）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRosterService {

    private final MemoryProjectMemberMapper memberMapper;

    /** 项目花名册（含 DEPARTED + departed_at，配 ACL 矩阵/召回人员多选源数据）。 */
    public List<MemoryRosterVO> getRoster(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        return memberMapper.findRoster(projectId);
    }
}
