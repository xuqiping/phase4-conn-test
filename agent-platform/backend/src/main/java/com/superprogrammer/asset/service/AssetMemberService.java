package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.asset.dto.MemberAddRequest;
import com.superprogrammer.asset.dto.MemberVO;
import com.superprogrammer.asset.dto.TransferRequest;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetProjectMember;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetProjectMemberMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目资产库·成员授权（plan §S3 / FR-002，设计方案 §七）。
 *
 * <p>能力：成员列表/邀请(角色)/改角色/移除/转让 owner。
 * 仅 owner 可操作成员（{@link AssetAclService#requireManage}）；**例外：成员可自移除（退出项目）**。
 *
 * <p>联动 L1：移除成员后项目从其「共享给我」列表消失；其在画布中已引用的资产快照不受影响
 * （引用的是版本快照 file_id，属引用方画布数据）。
 *
 * <p>审计：授权/改角色/移除/转让打日志（userId/projectId/动作/grantedBy），复用 media traceId 风格。
 *
 * <p>转让 owner = 旧 owner 降级 editor（落成员表），新 owner 升 owner_id（移除其成员行若有）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetMemberService {

    private final AssetProjectMapper projectMapper;
    private final AssetProjectMemberMapper memberMapper;
    private final AssetAclService aclService;

    /** 成员列表（owner 行合成居首）。 */
    public List<MemberVO> list(Long projectId, Long userId, boolean admin) {
        aclService.loadAccessible(projectId, userId, admin);
        AssetProject p = loadProject(projectId);
        List<MemberVO> result = new ArrayList<>();
        // owner 行合成居首
        result.add(MemberVO.builder()
                .userId(p.getOwnerId())
                .role("OWNER")
                .isOwner(true)
                .grantedBy(null)
                .grantedAt(p.getCreatedAt())
                .build());
        memberMapper.selectList(new LambdaQueryWrapper<AssetProjectMember>()
                        .eq(AssetProjectMember::getProjectId, projectId)
                        .eq(AssetProjectMember::getDeleted, 0)
                        .orderByAsc(AssetProjectMember::getCreatedAt))
                .forEach(m -> result.add(MemberVO.builder()
                        .userId(m.getUserId())
                        .role(m.getRole())
                        .isOwner(false)
                        .grantedBy(m.getGrantedBy())
                        .grantedAt(m.getCreatedAt())
                        .build()));
        return result;
    }

    /** 邀请成员（指定角色）。仅 owner。重复授权友好报错（数据库 UNIQUE 兜底，plan 坑点预判）。 */
    @Transactional
    public MemberVO invite(Long projectId, Long currentUserId, boolean admin, MemberAddRequest req) {
        aclService.requireManage(projectId, currentUserId, admin);
        AssetProject p = loadProject(projectId);
        if (req.getUserId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "被邀请用户不能为空");
        }
        if (req.getUserId().equals(p.getOwnerId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "所有者无需重复授权");
        }
        String role = validateRole(req.getRole());
        // 重复授权预检（UNIQUE(project_id,user_id) 兜底）
        Long exists = memberMapper.selectCount(new LambdaQueryWrapper<AssetProjectMember>()
                .eq(AssetProjectMember::getProjectId, projectId)
                .eq(AssetProjectMember::getUserId, req.getUserId())
                .eq(AssetProjectMember::getDeleted, 0));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "该用户已是项目成员");
        }
        AssetProjectMember m = new AssetProjectMember();
        m.setProjectId(projectId);
        m.setUserId(req.getUserId());
        m.setRole(role);
        m.setGrantedBy(currentUserId);
        memberMapper.insert(m);
        log.info("asset member invited: projectId={} targetUser={} role={} by={}", projectId, req.getUserId(), role, currentUserId);
        return MemberVO.builder()
                .userId(m.getUserId())
                .role(m.getRole())
                .isOwner(false)
                .grantedBy(m.getGrantedBy())
                .grantedAt(m.getCreatedAt())
                .build();
    }

    /** 改成员角色。仅 owner。 */
    @Transactional
    public void changeRole(Long projectId, Long currentUserId, boolean admin, Long targetUserId, String newRole) {
        aclService.requireManage(projectId, currentUserId, admin);
        loadProject(projectId);
        String role = validateRole(newRole);
        AssetProjectMember m = requireMember(projectId, targetUserId);
        m.setRole(role);
        memberMapper.updateById(m);
        log.info("asset member role changed: projectId={} targetUser={} newRole={} by={}", projectId, targetUserId, role, currentUserId);
    }

    /**
     * 移除成员（L1）。
     * 成员可自移除（退出项目）；移除他人仅 owner。
     * owner 不可被移除（owner 不在成员表；若试图移除 owner_id 报错）。
     */
    @Transactional
    public void remove(Long projectId, Long currentUserId, boolean admin, Long targetUserId) {
        AssetProject p;
        boolean selfLeave = targetUserId != null && targetUserId.equals(currentUserId);
        if (selfLeave) {
            // 自移除（退出）：仅需访问权即可退出
            aclService.loadAccessible(projectId, currentUserId, admin);
            p = loadProject(projectId);
            if (currentUserId.equals(p.getOwnerId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "所有者不可退出，请先转让项目");
            }
        } else {
            aclService.requireManage(projectId, currentUserId, admin);
            p = loadProject(projectId);
            if (targetUserId != null && targetUserId.equals(p.getOwnerId())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "所有者不可被移除");
            }
        }
        requireMember(projectId, targetUserId);
        memberMapper.delete(new LambdaQueryWrapper<AssetProjectMember>()
                .eq(AssetProjectMember::getProjectId, projectId)
                .eq(AssetProjectMember::getUserId, targetUserId));
        log.info("asset member removed: projectId={} targetUser={} by={} self={}", projectId, targetUserId, currentUserId, selfLeave);
    }

    /**
     * 转让所有者（FR-002 / L1）。仅 owner。
     * 新 owner=toUserId；旧 owner 降级 editor（落成员表）；新 owner 若为成员则移除其成员行。
     */
    @Transactional
    public void transfer(Long projectId, Long currentUserId, boolean admin, TransferRequest req) {
        aclService.requireManage(projectId, currentUserId, admin);
        AssetProject p = loadProject(projectId);
        if (req == null || req.getToUserId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新所有者不能为空");
        }
        Long toUserId = req.getToUserId();
        if (toUserId.equals(p.getOwnerId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新所有者与当前所有者相同");
        }
        // 新 owner 若已是成员，移除其成员行（owner 不落表）
        memberMapper.delete(new LambdaQueryWrapper<AssetProjectMember>()
                .eq(AssetProjectMember::getProjectId, projectId)
                .eq(AssetProjectMember::getUserId, toUserId));
        // 旧 owner 降级 editor
        AssetProjectMember oldOwnerMember = new AssetProjectMember();
        oldOwnerMember.setProjectId(projectId);
        oldOwnerMember.setUserId(p.getOwnerId());
        oldOwnerMember.setRole(AssetProjectMember.ROLE_EDITOR);
        oldOwnerMember.setGrantedBy(currentUserId);
        memberMapper.insert(oldOwnerMember);
        // 更新项目 owner
        p.setOwnerId(toUserId);
        projectMapper.updateById(p);
        log.info("asset project transferred: projectId={} fromOwner={} toOwner={} by={}",
                projectId, currentUserId, toUserId, currentUserId);
    }

    // ---------- 内部工具 ----------

    private String validateRole(String role) {
        if (!AssetProjectMember.ROLE_EDITOR.equals(role) && !AssetProjectMember.ROLE_VIEWER.equals(role)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色必须为 VIEWER 或 EDITOR");
        }
        return role;
    }

    private AssetProjectMember requireMember(Long projectId, Long userId) {
        AssetProjectMember m = memberMapper.selectOne(new LambdaQueryWrapper<AssetProjectMember>()
                .eq(AssetProjectMember::getProjectId, projectId)
                .eq(AssetProjectMember::getUserId, userId)
                .eq(AssetProjectMember::getDeleted, 0));
        if (m == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该成员不存在");
        }
        return m;
    }

    private AssetProject loadProject(Long projectId) {
        AssetProject p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return p;
    }
}
