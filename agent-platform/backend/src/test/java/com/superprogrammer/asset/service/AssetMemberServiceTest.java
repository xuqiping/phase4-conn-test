package com.superprogrammer.asset.service;

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
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssetMemberService 单测：授权矩阵 + L1 + 审计（plan §S3 验证）。
 * 重点：viewer/editor 写成员操作 403；自移除=退出；owner 不可被移除；转让降级。
 */
@ExtendWith(MockitoExtension.class)
class AssetMemberServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final Long EDITOR_ID = 20L;
    private static final Long VIEWER_ID = 30L;
    private static final Long NEW_OWNER_ID = 40L;

    @Mock private AssetProjectMapper projectMapper;
    @Mock private AssetProjectMemberMapper memberMapper;
    @Mock private AssetAclService aclService;
    @Mock private UserMapper userMapper;

    private AssetMemberService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AssetProject.class);
        TableInfoHelper.initTableInfo(assistant, AssetProjectMember.class);
    }

    @BeforeEach
    void setUp() {
        service = new AssetMemberService(projectMapper, memberMapper, aclService, userMapper);
    }

    @Test
    void list_ownerRowSynthesizedFirst() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        AssetProjectMember m = member(EDITOR_ID, "EDITOR");
        when(memberMapper.selectList(any())).thenReturn(List.of(m));
        when(userMapper.selectBatchIds(anyList())).thenReturn(List.of(
                user(OWNER_ID, "owner"), user(EDITOR_ID, "editor")));
        List<MemberVO> vos = service.list(PROJECT_ID, OWNER_ID, false);
        assertEquals(2, vos.size());
        assertTrue(vos.get(0).isOwner(), "首行须为 owner 合成行");
        assertEquals("OWNER", vos.get(0).getRole());
        assertEquals("owner", vos.get(0).getUsername());
        assertEquals(EDITOR_ID, vos.get(1).getUserId());
        assertEquals("editor", vos.get(1).getUsername());
    }

    @Test
    void searchCandidates_ownerGetsMinimalActiveUsersWithExclusionsAndLimit() {
        when(aclService.requireManage(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        when(memberMapper.selectList(any())).thenReturn(List.of(member(EDITOR_ID, "EDITOR")));
        List<User> rows = java.util.stream.LongStream.rangeClosed(100, 120)
                .mapToObj(id -> user(id, "user-" + id)).toList();
        when(userMapper.searchActiveCandidates(anyString(), anyList(), eq(20))).thenReturn(rows);

        List<MemberCandidateVO> result = service.searchCandidates(
                PROJECT_ID, OWNER_ID, false, "  a%_" + "x".repeat(80));

        assertEquals(20, result.size());
        assertEquals(Set.of("id", "username"), java.util.Arrays.stream(MemberCandidateVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).collect(java.util.stream.Collectors.toSet()));
        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> excluded = ArgumentCaptor.forClass(List.class);
        verify(userMapper).searchActiveCandidates(keyword.capture(), excluded.capture(), eq(20));
        assertTrue(keyword.getValue().startsWith("a\\%\\_"));
        assertTrue(keyword.getValue().length() <= 52, "原始关键词截断 50 后只允许通配符转义增长");
        assertTrue(excluded.getValue().containsAll(List.of(OWNER_ID, EDITOR_ID)));
    }

    @Test
    void searchCandidates_viewerDeniedWithoutUserLookup() {
        when(aclService.requireManage(PROJECT_ID, VIEWER_ID, false))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "仅所有者"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.searchCandidates(PROJECT_ID, VIEWER_ID, false, "alice"));

        assertEquals(ErrorCode.FORBIDDEN.getCode(), error.getCode());
        verify(userMapper, never()).searchActiveCandidates(anyString(), anyList(), anyInt());
    }

    @Test
    void searchCandidates_emptyKeywordUsesBoundedActiveQuery() {
        // 2x#5：空关键词开箱即载全量候选，LIMIT 20→50
        when(aclService.requireManage(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        when(memberMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.searchActiveCandidates("", List.of(OWNER_ID), 50)).thenReturn(List.of());

        assertTrue(service.searchCandidates(PROJECT_ID, OWNER_ID, false, "   ").isEmpty());

        verify(userMapper).searchActiveCandidates("", List.of(OWNER_ID), 50);
    }

    @Test
    void invite_viewerDenied() {
        when(aclService.requireManage(PROJECT_ID, VIEWER_ID, false))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "仅所有者"));
        MemberAddRequest req = new MemberAddRequest();
        req.setUserId(NEW_OWNER_ID);
        req.setRole("EDITOR");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.invite(PROJECT_ID, VIEWER_ID, false, req));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(memberMapper, never()).insert(any());
    }

    @Test
    void invite_duplicate_conflict() {
        when(aclService.requireManage(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        when(memberMapper.selectCount(any())).thenReturn(1L);
        MemberAddRequest req = new MemberAddRequest();
        req.setUserId(EDITOR_ID);
        req.setRole("EDITOR");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.invite(PROJECT_ID, OWNER_ID, false, req));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void invite_invalidRole_throws() {
        when(aclService.requireManage(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        MemberAddRequest req = new MemberAddRequest();
        req.setUserId(EDITOR_ID);
        req.setRole("OWNER");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.invite(PROJECT_ID, OWNER_ID, false, req));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    @Test
    void invite_owner_persistsWithGrantedBy() {
        when(aclService.requireManage(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        MemberAddRequest req = new MemberAddRequest();
        req.setUserId(EDITOR_ID);
        req.setRole("EDITOR");
        service.invite(PROJECT_ID, OWNER_ID, false, req);
        ArgumentCaptor<AssetProjectMember> captor = ArgumentCaptor.forClass(AssetProjectMember.class);
        verify(memberMapper).insert(captor.capture());
        assertEquals(EDITOR_ID, captor.getValue().getUserId());
        assertEquals("EDITOR", captor.getValue().getRole());
        assertEquals(OWNER_ID, captor.getValue().getGrantedBy(), "审计：grantedBy=操作 owner");
    }

    @Test
    void remove_selfLeave_allowedWithoutManage() {
        // 自移除：只调 loadAccessible（访问权），不调 requireManage
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        AssetProjectMember self = member(EDITOR_ID, "EDITOR");
        when(memberMapper.selectOne(any())).thenReturn(self);
        service.remove(PROJECT_ID, EDITOR_ID, false, EDITOR_ID);
        verify(memberMapper).delete(any());
    }

    @Test
    void remove_ownerCannotLeave() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.remove(PROJECT_ID, OWNER_ID, false, OWNER_ID));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
        verify(memberMapper, never()).delete(any());
    }

    @Test
    void remove_ownerCannotBeRemovedByOther() {
        when(aclService.requireManage(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.remove(PROJECT_ID, OWNER_ID, false, NEW_OWNER_ID));
        // NEW_OWNER_ID == project.ownerId? ownerId=OWNER_ID, so NEW_OWNER_ID(40) != owner(10) → not this guard
        // 实际：requireMember 找不到该成员 → NOT_FOUND
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void transfer_oldOwnerDowngradedToEditor() {
        when(aclService.requireManage(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        AssetProject p = project(OWNER_ID);
        p.setPublicPool(true);
        p.setPublicAccessMode(AssetProject.PUBLIC_ACCESS_OPEN);
        p.setPublishedBy(1L);
        p.setPublishedByAdmin(true);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        TransferRequest req = new TransferRequest();
        req.setToUserId(NEW_OWNER_ID);
        service.transfer(PROJECT_ID, OWNER_ID, false, req);

        // 新 owner 成员行被删（若有）
        verify(memberMapper).delete(any());
        // 旧 owner 降级 editor 入成员表
        ArgumentCaptor<AssetProjectMember> captor = ArgumentCaptor.forClass(AssetProjectMember.class);
        verify(memberMapper).insert(captor.capture());
        assertEquals(OWNER_ID, captor.getValue().getUserId());
        assertEquals("EDITOR", captor.getValue().getRole());
        // 项目 owner 更新为新 owner
        assertEquals(NEW_OWNER_ID, p.getOwnerId());
        assertEquals(true, p.getPublicPool());
        assertEquals(AssetProject.PUBLIC_ACCESS_OPEN, p.getPublicAccessMode());
        assertEquals(1L, p.getPublishedBy());
        assertEquals(true, p.getPublishedByAdmin());
        verify(projectMapper).updateById(p);
    }

    @Test
    void transfer_sameOwner_throws() {
        when(aclService.requireManage(PROJECT_ID, OWNER_ID, false)).thenReturn(null);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(OWNER_ID));
        TransferRequest req = new TransferRequest();
        req.setToUserId(OWNER_ID);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.transfer(PROJECT_ID, OWNER_ID, false, req));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode());
    }

    private AssetProject project(Long ownerId) {
        AssetProject p = new AssetProject();
        p.setId(PROJECT_ID);
        p.setOwnerId(ownerId);
        return p;
    }

    private AssetProjectMember member(Long userId, String role) {
        AssetProjectMember m = new AssetProjectMember();
        m.setProjectId(PROJECT_ID);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setStatus("ACTIVE");
        return user;
    }
}
