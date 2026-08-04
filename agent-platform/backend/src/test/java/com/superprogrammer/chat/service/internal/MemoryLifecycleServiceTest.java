package com.superprogrammer.chat.service.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.superprogrammer.chat.dto.MemoryLifecycleActionVO;
import com.superprogrammer.chat.entity.MemoryNotification;
import com.superprogrammer.chat.entity.MemoryProjectMember;
import com.superprogrammer.chat.mapper.MemoryNotificationMapper;
import com.superprogrammer.chat.mapper.MemoryProjectMemberMapper;
import com.superprogrammer.chat.mapper.MemoryTurnMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.project.dto.ProjectVO;
import com.superprogrammer.project.service.ProjectService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 计划12 · F-4b 前置 · MemoryLifecycleService 单测（Mockito）。
 * <p>
 * 覆盖（对齐 §3.7 边界 + 向量 7 IDOR）：
 * <ol>
 *   <li>copy-to：DEPARTED 本人放行（copy 非 move）/ 非成员 403 / ACTIVE 成员 403 / 原项目不存在 404。</li>
 *   <li>restore：有待拉取放行 + 波及通知置 resolved / 无待拉取 404（防存在性探测）。</li>
 *   <li>命名：空名走默认「「原项目名」记忆拉取」/ 指定名透传 / 超 100 字符截断。</li>
 *   <li>新栈 OWNER 成员行不再由本类自插（改由 ProjectService → MemoryLifecycleHookService 落）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MemoryLifecycleServiceTest {

    @Mock MemoryProjectMemberMapper memberMapper;
    @Mock MemoryTurnMapper turnMapper;
    @Mock MemoryNotificationMapper notificationMapper;
    @Mock ProjectService projectService;

    private MemoryLifecycleService service;

    /** 填充 MP lambda 缓存，使 LambdaUpdateWrapper.set() 能把 SFunction 解析为列名（承 IndexJobTxServiceTest 范式）。 */
    @BeforeAll
    static void initTableInfo() {
        Configuration cfg = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "");
        TableInfoHelper.initTableInfo(assistant, MemoryNotification.class);
        TableInfoHelper.initTableInfo(assistant, MemoryProjectMember.class);
    }

    @BeforeEach
    void setUp() {
        service = new MemoryLifecycleService(memberMapper, turnMapper, notificationMapper, projectService);
    }

    private MemoryProjectMember membership(String status) {
        MemoryProjectMember m = new MemoryProjectMember();
        m.setProjectId(100L);
        m.setUserId(1L);
        m.setStatus(status);
        return m;
    }

    private void stubProjectCreate() {
        when(projectService.create(any(), eq(1L)))
                .thenReturn(ProjectVO.builder().id(200L).name("新项目").build());
    }

    // ===== copy-to =====

    @Test
    void copyTo_departedMember_appendsToNewProject() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(membership("DEPARTED"));
        when(turnMapper.findProjectNameAnyState(100L)).thenReturn("原项目");
        stubProjectCreate();
        when(turnMapper.appendProjectToMyTurns(1L, 100L, 200L)).thenReturn(5);

        MemoryLifecycleActionVO vo = service.copyDepartedProjectTo(1L, 100L, null);

        assertEquals(200L, vo.getNewProjectId());
        assertEquals(5, vo.getAffectedTurns());
        // copy 非 move：只调追加，不调 restore（原挂载/departed 标记不动）
        verify(turnMapper).appendProjectToMyTurns(1L, 100L, 200L);
        verify(turnMapper, never()).restoreMyTurnsFromDeletedProject(anyLong(), anyLong(), anyLong());
        // 新栈 OWNER 成员行由 ProjectService.create → MemoryLifecycleHookService 落（本类不再自插）
        verify(memberMapper, never()).insert(any(MemoryProjectMember.class));
    }

    @Test
    void copyTo_activeMember_forbidden() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(membership("ACTIVE"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.copyDepartedProjectTo(1L, 100L, null));
        assertEquals(403, ex.getCode());
        verify(projectService, never()).create(any(), anyLong());
    }

    @Test
    void copyTo_nonMember_forbidden() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.copyDepartedProjectTo(1L, 100L, null));
        assertEquals(403, ex.getCode());
    }

    @Test
    void copyTo_projectMissing_notFound() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(membership("DEPARTED"));
        when(turnMapper.findProjectNameAnyState(100L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.copyDepartedProjectTo(1L, 100L, null));
        assertEquals(404, ex.getCode());
        verify(projectService, never()).create(any(), anyLong());
    }

    // ===== restore =====

    @Test
    void restore_withPendingTurns_restoresAndResolvesNotice() {
        when(turnMapper.countMyTurnsInDeletedProject(1L, 100L)).thenReturn(3);
        when(turnMapper.findProjectNameAnyState(100L)).thenReturn("被删项目");
        stubProjectCreate();
        when(turnMapper.restoreMyTurnsFromDeletedProject(1L, 100L, 200L)).thenReturn(3);

        MemoryLifecycleActionVO vo = service.restoreDeletedProject(1L, 100L, null);

        assertEquals(3, vo.getAffectedTurns());
        verify(turnMapper).restoreMyTurnsFromDeletedProject(1L, 100L, 200L);
        // 波及通知置 resolved（badge 消）
        verify(notificationMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void restore_noPendingTurns_notFound() {
        when(turnMapper.countMyTurnsInDeletedProject(1L, 100L)).thenReturn(0);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restoreDeletedProject(1L, 100L, null));
        assertEquals(404, ex.getCode());
        verify(projectService, never()).create(any(), anyLong());
        verify(notificationMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    // ===== 命名 =====

    @Test
    void createPullProject_blankName_defaultsToOldProjectName() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(membership("DEPARTED"));
        when(turnMapper.findProjectNameAnyState(100L)).thenReturn("旧项目X");
        stubProjectCreate();
        when(turnMapper.appendProjectToMyTurns(anyLong(), anyLong(), anyLong())).thenReturn(0);

        service.copyDepartedProjectTo(1L, 100L, "  ");

        verify(projectService).create(argThat(req -> "「旧项目X」记忆拉取".equals(req.getName())), eq(1L));
    }

    @Test
    void createPullProject_givenName_usedAsIs() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(membership("DEPARTED"));
        when(turnMapper.findProjectNameAnyState(100L)).thenReturn("旧项目X");
        stubProjectCreate();
        when(turnMapper.appendProjectToMyTurns(anyLong(), anyLong(), anyLong())).thenReturn(0);

        service.copyDepartedProjectTo(1L, 100L, "  我的新项目  ");

        verify(projectService).create(argThat(req -> "我的新项目".equals(req.getName())), eq(1L));
    }

    @Test
    void createPullProject_tooLongName_truncatedTo100() {
        when(memberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(membership("DEPARTED"));
        when(turnMapper.findProjectNameAnyState(100L)).thenReturn("旧");
        stubProjectCreate();
        when(turnMapper.appendProjectToMyTurns(anyLong(), anyLong(), anyLong())).thenReturn(0);
        String longName = "x".repeat(120);

        service.copyDepartedProjectTo(1L, 100L, longName);

        verify(projectService).create(argThat(req -> req.getName().length() == 100), eq(1L));
    }
}
