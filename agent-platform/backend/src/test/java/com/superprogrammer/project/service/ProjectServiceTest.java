package com.superprogrammer.project.service;

import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.chat.service.internal.MemoryLifecycleHookService;
import com.superprogrammer.project.dto.ProjectCreateRequest;
import com.superprogrammer.project.dto.ProjectShareRequest;
import com.superprogrammer.project.entity.Project;
import com.superprogrammer.project.entity.ProjectMember;
import com.superprogrammer.project.mapper.ProjectMapper;
import com.superprogrammer.project.mapper.ProjectMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · 生命周期写侧 hook · ProjectService 接线单测（Mockito）。
 * 验证四个写流程（create/addMember/removeMember/delete）均触发记忆新栈 hook，参数正确。
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock ProjectMapper projectMapper;
    @Mock ProjectMemberMapper memberMapper;
    @Mock UserMapper userMapper;
    @Mock MemoryLifecycleHookService hookService;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectMapper, memberMapper, userMapper, hookService);
    }

    private Project projectOwnedBy(long ownerId) {
        Project p = new Project();
        p.setId(100L);
        p.setName("测试项目");
        p.setCreatedBy(ownerId);
        return p;
    }

    @Test
    void create_triggersOnProjectCreated() {
        ProjectCreateRequest req = new ProjectCreateRequest();
        req.setName("新项目");
        // toVO 依赖 findMember/selectCount，mock 默认即可

        service.create(req, 1L);

        verify(hookService).onProjectCreated(any(), eq(1L));
    }

    @Test
    void addMember_newMember_triggersOnMemberAdded() {
        ProjectShareRequest req = new ProjectShareRequest();
        req.setUserId(2L);
        req.setRole("EDITOR");
        when(projectMapper.selectById(100L)).thenReturn(projectOwnedBy(1L));
        when(userMapper.selectById(2L)).thenReturn(new User());

        service.addMember(100L, req, 1L, false);

        verify(hookService).onMemberAdded(100L, 2L, "EDITOR");
    }

    @Test
    void addMember_existingMemberRoleChange_triggersOnMemberAdded() {
        ProjectShareRequest req = new ProjectShareRequest();
        req.setUserId(2L);
        req.setRole("VIEWER");
        when(projectMapper.selectById(100L)).thenReturn(projectOwnedBy(1L));
        when(userMapper.selectById(2L)).thenReturn(new User());
        ProjectMember existing = new ProjectMember();
        existing.setId(9L);
        existing.setProjectId(100L);
        existing.setUserId(2L);
        existing.setRole("EDITOR");
        existing.setDeleted(0);
        when(memberMapper.findAnyState(100L, 2L)).thenReturn(existing);

        service.addMember(100L, req, 1L, false);

        verify(hookService).onMemberAdded(100L, 2L, "VIEWER");
        verify(memberMapper, never()).insert(any(ProjectMember.class));
    }

    @Test
    void addMember_softDeletedRow_revivesInsteadOfInsert() {
        // 曾移除过的成员再加回：软删行复活（否则撞 uk_project_members_project_user，IT 暴露）
        ProjectShareRequest req = new ProjectShareRequest();
        req.setUserId(2L);
        req.setRole("EDITOR");
        when(projectMapper.selectById(100L)).thenReturn(projectOwnedBy(1L));
        when(userMapper.selectById(2L)).thenReturn(new User());
        ProjectMember softDeleted = new ProjectMember();
        softDeleted.setId(9L);
        softDeleted.setProjectId(100L);
        softDeleted.setUserId(2L);
        softDeleted.setRole("VIEWER");
        softDeleted.setDeleted(1);
        when(memberMapper.findAnyState(100L, 2L)).thenReturn(softDeleted);

        service.addMember(100L, req, 1L, false);

        verify(memberMapper).updateById(argThat(m ->
                m.getId().equals(9L) && m.getDeleted() == 0 && "EDITOR".equals(m.getRole())));
        verify(memberMapper, never()).insert(any(ProjectMember.class));
        verify(hookService).onMemberAdded(100L, 2L, "EDITOR");
    }

    @Test
    void removeMember_triggersOnMemberDeparted() {
        when(projectMapper.selectById(100L)).thenReturn(projectOwnedBy(1L));
        ProjectMember member = new ProjectMember();
        member.setId(9L);
        member.setProjectId(100L);
        member.setUserId(2L);
        member.setRole("EDITOR");
        when(memberMapper.selectById(9L)).thenReturn(member);

        service.removeMember(100L, 9L, 1L, false);

        verify(memberMapper).deleteById(9L);
        verify(hookService).onMemberDeparted(100L, 2L, "EDITOR");
    }

    @Test
    void delete_triggersOnProjectDeleted() {
        when(projectMapper.selectById(100L)).thenReturn(projectOwnedBy(1L));

        service.delete(100L, 1L, false);

        verify(projectMapper).deleteById(100L);
        // 二期 P1（V67）：hook 不再需项目名（turns 纯个人域，无波及通知）
        verify(hookService).onProjectDeleted(100L);
    }
}
