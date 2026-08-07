package com.superprogrammer.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.chat.service.internal.MemoryLifecycleHookService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.project.dto.ProjectCreateRequest;
import com.superprogrammer.project.dto.ProjectMemberVO;
import com.superprogrammer.project.dto.ProjectShareRequest;
import com.superprogrammer.project.dto.ProjectVO;
import com.superprogrammer.project.entity.Project;
import com.superprogrammer.project.entity.ProjectMember;
import com.superprogrammer.project.mapper.ProjectMapper;
import com.superprogrammer.project.mapper.ProjectMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目（记忆 scope 容器）CRUD + 共享，V33。
 * 访问模型：用户私有 + 可共享（照 Memory 用户私有范式 + project_members 共享，admin bypass）。
 * 不走 @RequirePermission 权限码——所有鉴权在 service 内做（owner/member/admin）。
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper memberMapper;
    private final UserMapper userMapper;
    /** 计划12 记忆新栈生命周期写侧 hook（§3.7）：成员/项目事件同步到 memory_* 新栈。单向依赖无环。 */
    private final MemoryLifecycleHookService memoryLifecycleHookService;

    // ---------- 查询 ----------

    /** 当前用户可见的项目（自己拥有 或 被共享为成员）。 */
    public List<ProjectVO> listForUser(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        Set<Long> memberProjectIds = memberProjectIds(userId);
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(Project::getCreatedBy, userId)
                        .or().in(!memberProjectIds.isEmpty(), Project::getId, memberProjectIds))
                .orderByAsc(Project::getSortOrder)
                .orderByDesc(Project::getCreatedAt);
        return projectMapper.selectList(wrapper).stream()
                .map(p -> toVO(p, userId))
                .collect(Collectors.toList());
    }

    public ProjectVO get(Long id, Long userId, boolean admin) {
        assertAccess(id, userId, admin);
        return toVO(ensureProject(id), userId);
    }

    /** 用户可读的全部项目 id（resolver 用：注入 scope 的 ∩ canUse 过滤源）。 */
    public Set<Long> listAccessibleProjectIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        Set<Long> ids = new HashSet<>(memberProjectIds(userId));
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Project::getCreatedBy, userId).select(Project::getId);
        projectMapper.selectList(wrapper).forEach(p -> ids.add(p.getId()));
        return ids;
    }

    // ---------- CRUD ----------

    @Transactional
    public ProjectVO create(ProjectCreateRequest req, Long userId) {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "项目名称不能为空");
        }
        Project project = new Project();
        project.setName(req.getName().trim());
        project.setDescription(req.getDescription());
        project.setIcon(req.getIcon());
        project.setSortOrder(req.getSortOrder() == null ? 0 : req.getSortOrder());
        project.setCreatedBy(userId);
        project.setUpdatedBy(userId);
        projectMapper.insert(project);

        // owner 自身落一条 OWNER 成员行
        ProjectMember owner = new ProjectMember();
        owner.setProjectId(project.getId());
        owner.setUserId(userId);
        owner.setRole("OWNER");
        owner.setCreatedBy(userId);
        owner.setUpdatedBy(userId);
        memberMapper.insert(owner);
        memoryLifecycleHookService.onProjectCreated(project.getId(), userId);

        return toVO(project, userId);
    }

    @Transactional
    public ProjectVO update(Long id, ProjectCreateRequest req, Long userId, boolean admin) {
        assertManage(id, userId, admin);
        Project project = ensureProject(id);
        if (req.getName() != null && !req.getName().isBlank()) {
            project.setName(req.getName().trim());
        }
        if (req.getDescription() != null) {
            project.setDescription(req.getDescription());
        }
        if (req.getIcon() != null) {
            project.setIcon(req.getIcon());
        }
        if (req.getSortOrder() != null) {
            project.setSortOrder(req.getSortOrder());
        }
        project.setUpdatedBy(userId);
        projectMapper.updateById(project);
        return toVO(project, userId);
    }

    @Transactional
    public void delete(Long id, Long userId, boolean admin) {
        assertManage(id, userId, admin);
        projectMapper.deleteById(id); // @TableLogic 软删
        // 成员行随之失效：canAccess 先查项目存在性，软删项目后即不可见，成员残留无害
        // 计划12：记忆新栈级联（清总结/coverage/成员行/scope/开关 + 二期 P1 补清收录规则/条目，§3.7）
        // 二期 P1（V67）：turns 纯个人域——无 turns 标记、无波及通知，故不再需要项目名参数
        memoryLifecycleHookService.onProjectDeleted(id);
    }

    // ---------- 成员/共享 ----------

    public List<ProjectMemberVO> listMembers(Long id, Long userId, boolean admin) {
        assertAccess(id, userId, admin);
        LambdaQueryWrapper<ProjectMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectMember::getProjectId, id).orderByAsc(ProjectMember::getId);
        return memberMapper.selectList(wrapper).stream()
                .map(this::toMemberVO)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProjectMemberVO addMember(Long id, ProjectShareRequest req, Long operatorId, boolean admin) {
        assertManage(id, operatorId, admin);
        if (req == null || req.getUserId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "被共享用户不能为空");
        }
        User target = userMapper.selectById(req.getUserId());
        if (target == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "被共享用户不存在");
        }
        String role = (req.getRole() == null || "OWNER".equalsIgnoreCase(req.getRole()))
                ? "VIEWER" : req.getRole().toUpperCase();

        ProjectMember existing = memberMapper.findAnyState(id, req.getUserId());
        if (existing != null && existing.getDeleted() != null && existing.getDeleted() == 1) {
            // 曾移除过的软删行：复活（唯一约束含软删行，直接 insert 会撞 uk_project_members_project_user）
            existing.setDeleted(0);
            existing.setRole(role);
            existing.setUpdatedBy(operatorId);
            memberMapper.updateById(existing);
            memoryLifecycleHookService.onMemberAdded(id, req.getUserId(), role);
            return toMemberVO(existing);
        }
        if (existing != null) {
            existing.setRole(role);
            existing.setUpdatedBy(operatorId);
            memberMapper.updateById(existing);
            memoryLifecycleHookService.onMemberAdded(id, req.getUserId(), role);
            return toMemberVO(existing);
        }
        ProjectMember member = new ProjectMember();
        member.setProjectId(id);
        member.setUserId(req.getUserId());
        member.setRole(role);
        member.setCreatedBy(operatorId);
        member.setUpdatedBy(operatorId);
        memberMapper.insert(member);
        memoryLifecycleHookService.onMemberAdded(id, req.getUserId(), role);
        return toMemberVO(member);
    }

    @Transactional
    public void removeMember(Long id, Long memberId, Long operatorId, boolean admin) {
        assertManage(id, operatorId, admin);
        ProjectMember member = memberMapper.selectById(memberId);
        if (member == null || !id.equals(member.getProjectId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "成员不存在");
        }
        if ("OWNER".equals(member.getRole())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能移除项目拥有者");
        }
        memberMapper.deleteById(memberId);
        // 计划12：记忆新栈置 DEPARTED + departed_at + 本人 turns 追加 departed_project_ids（§3.7 保交接）
        memoryLifecycleHookService.onMemberDeparted(id, member.getUserId(), member.getRole());
    }

    // ---------- 鉴权 ----------

    public boolean canAccess(Long projectId, Long userId, boolean admin) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            return false;
        }
        if (admin || (userId != null && userId.equals(project.getCreatedBy()))) {
            return true;
        }
        return findMember(projectId, userId) != null;
    }

    public boolean canManage(Long projectId, Long userId, boolean admin) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            return false;
        }
        return admin || (userId != null && userId.equals(project.getCreatedBy()));
    }

    private void assertAccess(Long id, Long userId, boolean admin) {
        if (!canAccess(id, userId, admin)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在或无权访问");
        }
    }

    private void assertManage(Long id, Long userId, boolean admin) {
        if (!canManage(id, userId, admin)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员或项目拥有者可执行此操作");
        }
    }

    // ---------- 内部 ----------

    private Set<Long> memberProjectIds(Long userId) {
        LambdaQueryWrapper<ProjectMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectMember::getUserId, userId).select(ProjectMember::getProjectId);
        return memberMapper.selectList(wrapper).stream()
                .map(ProjectMember::getProjectId)
                .collect(Collectors.toSet());
    }

    private ProjectMember findMember(Long projectId, Long userId) {
        if (projectId == null || userId == null) {
            return null;
        }
        LambdaQueryWrapper<ProjectMember> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, userId);
        return memberMapper.selectOne(wrapper);
    }

    private Project ensureProject(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return project;
    }

    private ProjectVO toVO(Project p, Long userId) {
        ProjectMember myMembership = findMember(p.getId(), userId);
        String myRole = (userId != null && userId.equals(p.getCreatedBy())) ? "OWNER"
                : (myMembership != null ? myMembership.getRole() : null);
        Long memberCount = memberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, p.getId()));
        return ProjectVO.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .icon(p.getIcon())
                .sortOrder(p.getSortOrder())
                .ownerId(p.getCreatedBy())
                .createdAt(p.getCreatedAt())
                .myRole(myRole)
                .memberCount(memberCount)
                .build();
    }

    private ProjectMemberVO toMemberVO(ProjectMember m) {
        User user = userMapper.selectById(m.getUserId());
        return ProjectMemberVO.builder()
                .id(m.getId())
                .projectId(m.getProjectId())
                .userId(m.getUserId())
                .username(user == null ? null : user.getUsername())
                .role(m.getRole())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
