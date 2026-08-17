package com.superprogrammer.asset.service;

import com.superprogrammer.asset.dto.PublicPublishRequest;
import com.superprogrammer.asset.dto.ProjectAssetCountVO;
import com.superprogrammer.asset.dto.PublicProjectSummaryVO;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetPublicAccessRequest;
import com.superprogrammer.asset.enums.AssetRole;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetPublicAccessRequestMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetPublicPoolServiceTest {

    @Mock private AssetProjectMapper projectMapper;
    @Mock private AssetPublicAccessRequestMapper requestMapper;
    @Mock private AssetMapper assetMapper;
    @Mock private UserMapper userMapper;
    @Mock private AssetAclService aclService;

    @InjectMocks
    private AssetPublicPoolService service;

    @Test
    void publish_ownerStoresChosenModeAndPublisherSnapshot() {
        // AC-F19-01：普通 OWNER 可选择审批模式，官方标记为 false。
        AssetProject project = project(1L, 10L);
        when(projectMapper.selectById(1L)).thenReturn(project);
        when(aclService.requireManage(1L, 10L, false)).thenReturn(AssetRole.OWNER);
        PublicPublishRequest request = new PublicPublishRequest();
        request.setAccessMode(AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED);

        service.publish(1L, 10L, false, request);

        ArgumentCaptor<AssetProject> captor = ArgumentCaptor.forClass(AssetProject.class);
        verify(projectMapper).updateById(captor.capture());
        AssetProject updated = captor.getValue();
        assertThat(updated.getPublicPool()).isTrue();
        assertThat(updated.getPublicAccessMode()).isEqualTo("APPROVAL_REQUIRED");
        assertThat(updated.getPublishedBy()).isEqualTo(10L);
        assertThat(updated.getPublishedAt()).isNotNull();
        assertThat(updated.getPublishedByAdmin()).isFalse();
    }

    @Test
    void publish_adminForcesOpenAndOfficialSnapshot() {
        // AC-F19-01：管理员不受请求模式影响，固定 OPEN 且记录官方快照。
        AssetProject project = project(1L, 99L);
        when(projectMapper.selectById(1L)).thenReturn(project);
        when(aclService.requireManage(1L, 1L, true)).thenReturn(AssetRole.OWNER);
        PublicPublishRequest request = new PublicPublishRequest();
        request.setAccessMode(AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED);

        service.publish(1L, 1L, true, request);

        assertThat(project.getPublicAccessMode()).isEqualTo("OPEN");
        assertThat(project.getPublishedBy()).isEqualTo(1L);
        assertThat(project.getPublishedByAdmin()).isTrue();
    }

    @Test
    void publish_alreadyPublic_throwsConflict() {
        // AC-F19-01：不能用重复发布悄悄改写发布人/官方快照。
        AssetProject project = project(1L, 10L);
        project.setPublicPool(true);
        project.setPublishedBy(3L);
        when(projectMapper.selectById(1L)).thenReturn(project);
        when(aclService.requireManage(1L, 10L, false)).thenReturn(AssetRole.OWNER);
        PublicPublishRequest request = new PublicPublishRequest();
        request.setAccessMode(AssetProject.PUBLIC_ACCESS_OPEN);

        assertThatThrownBy(() -> service.publish(1L, 10L, false, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已发布");
        assertThat(project.getPublishedBy()).isEqualTo(3L);
    }

    @Test
    void publish_allowPublicCopyExplicitOverwrites_nullKeepsPreviousValue() {
        // V100：显式传值才覆盖；null=沿用当前值（跨再发布保留，发布弹窗回显依据）
        AssetProject project = project(1L, 10L);
        project.setAllowPublicCopy(false); // 上次发布选了「关」
        when(projectMapper.selectById(1L)).thenReturn(project);
        when(aclService.requireManage(1L, 10L, false)).thenReturn(AssetRole.OWNER);
        PublicPublishRequest keepNull = new PublicPublishRequest();
        keepNull.setAccessMode(AssetProject.PUBLIC_ACCESS_OPEN);

        service.publish(1L, 10L, false, keepNull);
        assertThat(project.getAllowPublicCopy()).isFalse(); // 未传 → 保留 false

        // 模拟「移出公众池后重新发布」（unpublish 不清 allowPublicCopy）
        project.setPublicPool(false);
        PublicPublishRequest turnOn = new PublicPublishRequest();
        turnOn.setAccessMode(AssetProject.PUBLIC_ACCESS_OPEN);
        turnOn.setAllowPublicCopy(true);
        service.publish(1L, 10L, false, turnOn);
        assertThat(project.getAllowPublicCopy()).isTrue(); // 显式 true → 覆盖
    }

    @Test
    void publish_adminCanAlsoSetAllowPublicCopy() {
        // C6：admin 代发同样可设开关（accessMode 被强制 OPEN，allowPublicCopy 仍取请求值）
        AssetProject project = project(1L, 99L);
        when(projectMapper.selectById(1L)).thenReturn(project);
        when(aclService.requireManage(1L, 1L, true)).thenReturn(AssetRole.OWNER);
        PublicPublishRequest request = new PublicPublishRequest();
        request.setAccessMode(AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED);
        request.setAllowPublicCopy(false);

        service.publish(1L, 1L, true, request);

        assertThat(project.getPublicAccessMode()).isEqualTo("OPEN");
        assertThat(project.getAllowPublicCopy()).isFalse();
    }

    @Test
    void unpublish_clearsSnapshotAndRevokesActivePublicRequests() {
        // AC-F19-01/02：移出后 PENDING/APPROVED 立即失效，成员授权不动。
        AssetProject project = project(1L, 10L);
        project.setPublicPool(true);
        project.setPublicAccessMode(AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED);
        project.setPublishedBy(10L);
        project.setPublishedAt(java.time.OffsetDateTime.now());
        when(projectMapper.selectById(1L)).thenReturn(project);
        when(aclService.requireManage(1L, 10L, false)).thenReturn(AssetRole.OWNER);

        service.unpublish(1L, 10L, false);

        assertThat(project.getPublicPool()).isFalse();
        assertThat(project.getPublicAccessMode()).isNull();
        assertThat(project.getPublishedBy()).isNull();
        assertThat(project.getPublishedAt()).isNull();
        assertThat(project.getPublishedByAdmin()).isFalse();
        ArgumentCaptor<AssetPublicAccessRequest> requestCaptor =
                ArgumentCaptor.forClass(AssetPublicAccessRequest.class);
        verify(requestMapper).update(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().getStatus()).isEqualTo("REVOKED");
        assertThat(requestCaptor.getValue().getDecidedBy()).isEqualTo(10L);
        assertThat(requestCaptor.getValue().getDecidedAt()).isNotNull();
    }

    @Test
    void listPublic_returnsBatchedSummaryAndUsabilityWithoutContentFields() {
        // AC-F19-01/02：公众池只返回摘要，计数/发布者/本人状态均批查一次。
        AssetProject open = project(1L, 10L);
        open.setPublicPool(true);
        open.setPublicAccessMode(AssetProject.PUBLIC_ACCESS_OPEN);
        open.setPublishedBy(5L);
        open.setPublishedAt(java.time.OffsetDateTime.parse("2026-08-10T01:00:00Z"));
        open.setMediaTypes("[\"图片\",\"视频\"]");
        AssetProject approval = project(2L, 11L);
        approval.setPublicPool(true);
        approval.setPublicAccessMode(AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED);
        approval.setPublishedBy(6L);
        approval.setPublishedAt(java.time.OffsetDateTime.parse("2026-08-10T02:00:00Z"));
        when(projectMapper.selectList(any())).thenReturn(java.util.List.of(approval, open));
        when(assetMapper.countByProjectIds(any())).thenReturn(java.util.List.of(
                new ProjectAssetCountVO(1L, 3L), new ProjectAssetCountVO(2L, 4L)));
        User publisher1 = new User();
        publisher1.setId(5L);
        publisher1.setUsername("publisher-a");
        User publisher2 = new User();
        publisher2.setId(6L);
        publisher2.setUsername("publisher-b");
        when(userMapper.selectBatchIds(any())).thenReturn(java.util.List.of(publisher1, publisher2));
        AssetPublicAccessRequest approved = new AssetPublicAccessRequest();
        approved.setProjectId(2L);
        approved.setApplicantId(20L);
        approved.setStatus(AssetPublicAccessRequest.STATUS_APPROVED);
        when(requestMapper.selectList(any())).thenReturn(java.util.List.of(approved));
        // V100：先设开关再单次拉取（null 视为 TRUE——迁移 DEFAULT 兼容存量行）
        approval.setAllowPublicCopy(false);

        java.util.List<PublicProjectSummaryVO> result = service.listPublic(20L, false);

        assertThat(result.get(0).getAllowPublicCopy()).isFalse();
        assertThat(result.get(1).getAllowPublicCopy()).isTrue();

        assertThat(result).extracting("id", "assetCount", "publisherUsername", "usable")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2L, 4L, "publisher-b", true),
                        org.assertj.core.groups.Tuple.tuple(1L, 3L, "publisher-a", true));
        assertThat(result.get(0).getMyRequestStatus()).isEqualTo("APPROVED");
        assertThat(result.get(1).getMediaTypes()).isEqualTo("[\"图片\",\"视频\"]");
        // 2x#4：mediaTypes 加入白名单（选择器按图片/视频过滤公共池项目）；叙事角色/资产内容仍禁止
        assertThat(java.util.Arrays.stream(PublicProjectSummaryVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .doesNotContain("narrativeRoles", "assets", "content", "fileId");
        verify(projectMapper).selectList(any());
        verify(assetMapper).countByProjectIds(any());
        verify(userMapper).selectBatchIds(any());
        verify(requestMapper).selectList(any());
    }

    private AssetProject project(Long id, Long ownerId) {
        AssetProject project = new AssetProject();
        project.setId(id);
        project.setOwnerId(ownerId);
        project.setName("公开项目");
        project.setPublicPool(false);
        project.setPublishedByAdmin(false);
        return project;
    }
}
