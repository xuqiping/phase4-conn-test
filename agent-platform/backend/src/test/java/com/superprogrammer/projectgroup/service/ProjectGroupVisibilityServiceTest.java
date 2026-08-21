package com.superprogrammer.projectgroup.service;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import com.superprogrammer.projectgroup.entity.ProjectGroupEntity;
import com.superprogrammer.projectgroup.entity.ProjectGroupMemberEntity;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMapper;
import com.superprogrammer.projectgroup.mapper.ProjectGroupMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可见性三层判定（17x#2，V139）：成员覆盖 > 组模块覆盖 > 组默认；
 * MANAGER 恒可见；成员级覆盖写侧白名单与角色闸。
 */
@ExtendWith(MockitoExtension.class)
class ProjectGroupVisibilityServiceTest {

    private static final long GROUP_ID = 10L;
    private static final long OWNER = 1L;
    private static final long MEMBER = 2L;
    private static final long OTHER = 3L;

    @Mock private ProjectGroupMapper groupMapper;
    @Mock private ProjectGroupMemberMapper memberMapper;
    @Mock private MediaGenTaskMapper mediaTaskMapper;
    @Mock private ProjectGroupService groupService;

    @InjectMocks
    private ProjectGroupVisibilityService service;

    private ProjectGroupEntity group;

    @BeforeEach
    void setUp() {
        group = new ProjectGroupEntity();
        group.setId(GROUP_ID);
        group.setOwnerUserId(OWNER);
        group.setDeleted(0);
        group.setMemberOutputVisibility(ProjectGroupEntity.VIS_OWN);
    }

    @Test
    void 三层优先级_成员覆盖高于组覆盖高于默认() {
        // 组默认 OWN + 组覆盖 VIDEO=ALL + 成员覆盖 VIDEO=OWN → 成员赢
        group.setModuleVisibilityOverrides("{\"VIDEO\":\"ALL\"}");
        assertThat(service.effectiveVisibilityForOwner(group, "{\"VIDEO\":\"OWN\"}", "VIDEO"))
                .isEqualTo(ProjectGroupEntity.VIS_OWN);
        // 成员无该 key → 落组覆盖
        assertThat(service.effectiveVisibilityForOwner(group, "{\"IMAGE\":\"OWN\"}", "VIDEO"))
                .isEqualTo(ProjectGroupEntity.VIS_ALL);
        // 成员无覆盖 → 落组覆盖
        assertThat(service.effectiveVisibilityForOwner(group, null, "VIDEO"))
                .isEqualTo(ProjectGroupEntity.VIS_ALL);
        // 组覆盖也无（CHAT）→ 落组默认 OWN
        assertThat(service.effectiveVisibilityForOwner(group, null, "CHAT"))
                .isEqualTo(ProjectGroupEntity.VIS_OWN);
        // 坏 JSON 宽容回落组级
        assertThat(service.effectiveVisibilityForOwner(group, "bad-json", "VIDEO"))
                .isEqualTo(ProjectGroupEntity.VIS_ALL);
    }

    @Test
    void 可见判定_绕过与成员覆盖收紧放开() {
        // 本人/组长/admin 恒可见
        assertThat(service.canSeeOutputResolved(group, OTHER, false, "CHAT", OTHER, "MEMBER", null)).isTrue();
        assertThat(service.canSeeOutputResolved(group, OWNER, false, "CHAT", OTHER, "OWNER", null)).isTrue();
        assertThat(service.canSeeOutputResolved(group, 99L, true, "CHAT", OTHER, null, null)).isTrue();
        // MANAGER 恒可见（组默认 OWN 也放行）
        assertThat(service.canSeeOutputResolved(group, MEMBER, false, "CHAT", OTHER,
                ProjectGroupMemberEntity.ROLE_MANAGER, null)).isTrue();
        // 组默认 OWN，普通成员看他人 → 不可见
        assertThat(service.canSeeOutputResolved(group, MEMBER, false, "CHAT", OTHER, "MEMBER", null)).isFalse();
        // 归属人成员覆盖 ALL → 放开
        assertThat(service.canSeeOutputResolved(group, MEMBER, false, "CHAT", OTHER,
                "MEMBER", "{\"CHAT\":\"ALL\"}")).isTrue();
        // 组默认 ALL，归属人成员覆盖 OWN → 收紧
        group.setMemberOutputVisibility(ProjectGroupEntity.VIS_ALL);
        assertThat(service.canSeeOutputResolved(group, MEMBER, false, "CHAT", OTHER, "MEMBER", null)).isTrue();
        assertThat(service.canSeeOutputResolved(group, MEMBER, false, "CHAT", OTHER,
                "MEMBER", "{\"CHAT\":\"OWN\"}")).isFalse();
    }

    @Test
    void 成员覆盖写侧_角色闸与白名单() {
        ProjectGroupMemberEntity target = new ProjectGroupMemberEntity();
        target.setGroupId(GROUP_ID);
        target.setUserId(MEMBER);
        target.setRole(ProjectGroupMemberEntity.ROLE_MEMBER);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(target);

        // 非法模块 400
        assertThatThrownBy(() -> service.updateMemberVisibility(GROUP_ID, OWNER, false, MEMBER, Map.of("HACK", "ALL")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未知产出模块");
        // 非法值 400
        assertThatThrownBy(() -> service.updateMemberVisibility(GROUP_ID, OWNER, false, MEMBER, Map.of("CHAT", "X")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("OWN/ALL");
        // 合法设置 + 空 map 清空：两次写库
        assertThatCode(() -> service.updateMemberVisibility(GROUP_ID, OWNER, false, MEMBER, Map.of("VIDEO", "OWN")))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.updateMemberVisibility(GROUP_ID, OWNER, false, MEMBER, Map.of()))
                .doesNotThrowAnyException();
        verify(memberMapper, org.mockito.Mockito.times(2)).updateById(any(ProjectGroupMemberEntity.class));
        // null 不动（不触发第三次写）
        assertThatCode(() -> service.updateMemberVisibility(GROUP_ID, OWNER, false, MEMBER, null))
                .doesNotThrowAnyException();
        verify(memberMapper, org.mockito.Mockito.times(2)).updateById(any(ProjectGroupMemberEntity.class));
    }

    @Test
    void 成员覆盖写侧_目标仅MEMBER行() {
        ProjectGroupMemberEntity managerRow = new ProjectGroupMemberEntity();
        managerRow.setGroupId(GROUP_ID);
        managerRow.setUserId(MEMBER);
        managerRow.setRole(ProjectGroupMemberEntity.ROLE_MANAGER);
        when(memberMapper.selectByGroupUser(GROUP_ID, MEMBER)).thenReturn(managerRow);

        assertThatThrownBy(() -> service.updateMemberVisibility(GROUP_ID, OWNER, false, MEMBER, Map.of("CHAT", "ALL")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("恒可见");
        verify(memberMapper, never()).updateById(any(ProjectGroupMemberEntity.class));
    }

    @Test
    void 辅助解析_parseOverrides与任一ALL模块集() {
        assertThat(ProjectGroupVisibilityService.parseOverrides(null)).isNull();
        assertThat(ProjectGroupVisibilityService.parseOverrides("bad")).isNull();
        assertThat(ProjectGroupVisibilityService.parseOverrides("{\"VIDEO\":\"OWN\",\"HACK\":\"ALL\",\"CHAT\":\"X\"}"))
                .isEqualTo(Map.of("VIDEO", "OWN"));
        assertThat(service.kindsAnyMemberOverrideAll(
                java.util.Arrays.asList("{\"VIDEO\":\"ALL\"}", "{\"IMAGE\":\"ALL\"}", null)))
                .containsExactly("IMAGE", "VIDEO");
        assertThat(service.kindsAnyMemberOverrideAll(List.of("{\"VIDEO\":\"OWN\"}"))).isEmpty();
    }
}
