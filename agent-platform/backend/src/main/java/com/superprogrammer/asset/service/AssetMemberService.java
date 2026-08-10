package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.asset.dto.MemberAddRequest;
import com.superprogrammer.asset.dto.MemberCandidateVO;
import com.superprogrammer.asset.dto.MemberVO;
import com.superprogrammer.asset.dto.TransferRequest;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetProjectMember;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetProjectMemberMapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final UserMapper userMapper;

    /** 成员列表（owner 行合成居首）。 */
    public List<MemberVO> list(Long projectId, Long userId, boolean admin) {
        aclService.loadAccessible(projectId, userId, admin);
        AssetProject p = loadProject(projectId);
        List<AssetProjectMember> members = memberMapper.selectList(
                new LambdaQueryWrapper<AssetProjectMember>()
                        .eq(AssetProjectMember::getProjectId, projectId)
                        .eq(AssetProjectMember::getDeleted, 0)
                        .orderByAsc(AssetProjectMember::getCreatedAt));
        List<Long> userIds = new ArrayList<>();
        userIds.add(p.getOwnerId());
        members.stream().map(AssetProjectMember::getUserId).forEach(userIds::add);
        Map<Long, User> users = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<MemberVO> result = new ArrayList<>();
        // owner 行合成居首
        result.add(MemberVO.builder()
                .userId(p.getOwnerId())
                .username(usernameOf(users.get(p.getOwnerId())))
                .role("OWNER")
                .isOwner(true)
                .grantedBy(null)
                .grantedAt(p.getCreatedAt())
                .build());
        members.forEach(m -> result.add(MemberVO.builder()
                        .userId(m.getUserId())
                        .username(usernameOf(users.get(m.getUserId())))
                        .role(m.getRole())
                        .isOwner(false)
                        .grantedBy(m.getGrantedBy())
                        .grantedAt(m.getCreatedAt())
                        .build()));
        return result;
    }

    /** OWNER/admin 使用的资产域候选搜索，不依赖 user:manage。 */
    public List<MemberCandidateVO> searchCandidates(Long projectId, Long currentUserId,
                                                     boolean admin, String keyword) {
        aclService.requireManage(projectId, currentUserId, admin);
        AssetProject project = loadProject(projectId);
        Set<Long> excluded = new LinkedHashSet<>();
        excluded.add(project.getOwnerId());
        if (currentUserId != null) {
            excluded.add(currentUserId);
        }
        memberMapper.selectList(new LambdaQueryWrapper<AssetProjectMember>()
                        .eq(AssetProjectMember::getProjectId, projectId)
                        .eq(AssetProjectMember::getDeleted, 0))
                .stream().map(AssetProjectMember::getUserId).forEach(excluded::add);
        String safeKeyword = escapeLikeKeyword(keyword);
        return userMapper.searchActiveCandidates(safeKeyword, new ArrayList<>(excluded), 20)
                .stream().limit(20)
                .map(user -> new MemberCandidateVO(user.getId(), user.getUsername()))
                .toList();
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
        User invitedUser = userMapper.selectById(m.getUserId());
        log.info("asset member invited: projectId={} targetUser={} role={} by={}", projectId, req.getUserId(), role, currentUserId);
        return MemberVO.builder()
                .userId(m.getUserId())
                .username(usernameOf(invitedUser))
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

    private String escapeLikeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > 50) {
            trimmed = trimmed.substring(0, 50);
        }
        return trimmed.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private String usernameOf(User user) {
        return user == null ? null : user.getUsername();
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
