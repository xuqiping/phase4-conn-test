package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.superprogrammer.asset.dto.ProjectAssetCountVO;
import com.superprogrammer.asset.dto.PublicProjectSummaryVO;
import com.superprogrammer.asset.dto.PublicPublishRequest;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetPublicAccessRequest;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetPublicAccessRequestMapper;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 资产公众池的发布快照、摘要列表和移出操作。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetPublicPoolService {

    private static final Set<String> ACCESS_MODES = Set.of(
            AssetProject.PUBLIC_ACCESS_OPEN,
            AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED);

    private final AssetProjectMapper projectMapper;
    private final AssetPublicAccessRequestMapper requestMapper;
    private final AssetMapper assetMapper;
    private final UserMapper userMapper;
    private final AssetAclService aclService;

    @Transactional(rollbackFor = Exception.class)
    public void publish(Long projectId, Long userId, boolean admin, PublicPublishRequest request) {
        aclService.requireManage(projectId, userId, admin);
        AssetProject project = loadProject(projectId);
        if (Boolean.TRUE.equals(project.getPublicPool())) {
            throw new BusinessException(ErrorCode.CONFLICT, "项目已发布到公众池");
        }
        String mode = admin ? AssetProject.PUBLIC_ACCESS_OPEN
                : request == null ? null : request.getAccessMode();
        if (!ACCESS_MODES.contains(mode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "公开访问模式须为 OPEN 或 APPROVAL_REQUIRED");
        }
        project.setPublicPool(true);
        project.setPublicAccessMode(mode);
        project.setPublishedBy(userId);
        project.setPublishedAt(OffsetDateTime.now());
        project.setPublishedByAdmin(admin);
        projectMapper.updateById(project);
        log.info("asset public pool published: projectId={} userId={} mode={} official={}",
                projectId, userId, mode, admin);
    }

    @Transactional(rollbackFor = Exception.class)
    public void unpublish(Long projectId, Long userId, boolean admin) {
        aclService.requireManage(projectId, userId, admin);
        AssetProject project = loadProject(projectId);
        if (!Boolean.TRUE.equals(project.getPublicPool())) {
            return;
        }
        project.setPublicPool(false);
        project.setPublicAccessMode(null);
        project.setPublishedBy(null);
        project.setPublishedAt(null);
        project.setPublishedByAdmin(false);
        projectMapper.updateById(project);

        AssetPublicAccessRequest revoked = new AssetPublicAccessRequest();
        revoked.setStatus(AssetPublicAccessRequest.STATUS_REVOKED);
        revoked.setDecidedBy(userId);
        revoked.setDecidedAt(OffsetDateTime.now());
        int revokedCount = requestMapper.update(revoked,
                new LambdaUpdateWrapper<AssetPublicAccessRequest>()
                        .eq(AssetPublicAccessRequest::getProjectId, projectId)
                        .in(AssetPublicAccessRequest::getStatus,
                                AssetPublicAccessRequest.STATUS_PENDING,
                                AssetPublicAccessRequest.STATUS_APPROVED));
        log.info("asset public pool unpublished: projectId={} userId={} revokedRequests={}",
                projectId, userId, revokedCount);
    }

    public List<PublicProjectSummaryVO> listPublic(Long userId, boolean admin) {
        List<AssetProject> projects = projectMapper.selectList(
                new LambdaQueryWrapper<AssetProject>()
                        .eq(AssetProject::getPublicPool, true)
                        .eq(AssetProject::getDeleted, 0)
                        .orderByDesc(AssetProject::getPublishedAt));
        if (projects == null || projects.isEmpty()) {
            return List.of();
        }
        List<Long> projectIds = projects.stream().map(AssetProject::getId).toList();
        Map<Long, Long> counts = assetMapper.countByProjectIds(projectIds).stream()
                .collect(Collectors.toMap(ProjectAssetCountVO::getProjectId,
                        ProjectAssetCountVO::getAssetCount));

        Set<Long> publisherIds = projects.stream().map(AssetProject::getPublishedBy)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> publishers = publisherIds.isEmpty() ? Collections.emptyMap()
                : userMapper.selectBatchIds(publisherIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        Map<Long, String> requestStatuses = userId == null ? Collections.emptyMap()
                : requestMapper.selectList(new LambdaQueryWrapper<AssetPublicAccessRequest>()
                                .in(AssetPublicAccessRequest::getProjectId, projectIds)
                                .eq(AssetPublicAccessRequest::getApplicantId, userId)
                                .eq(AssetPublicAccessRequest::getDeleted, 0))
                        .stream().collect(Collectors.toMap(
                                AssetPublicAccessRequest::getProjectId,
                                AssetPublicAccessRequest::getStatus,
                                (left, right) -> left));

        return projects.stream().map(project -> {
            String requestStatus = requestStatuses.get(project.getId());
            boolean usable = admin
                    || java.util.Objects.equals(project.getOwnerId(), userId)
                    || AssetProject.PUBLIC_ACCESS_OPEN.equals(project.getPublicAccessMode())
                    || AssetPublicAccessRequest.STATUS_APPROVED.equals(requestStatus);
            User publisher = publishers.get(project.getPublishedBy());
            return PublicProjectSummaryVO.builder()
                    .id(project.getId())
                    .name(project.getName())
                    .description(project.getDescription())
                    .coverFileId(project.getCoverFileId())
                    .publicAccessMode(project.getPublicAccessMode())
                    .publishedBy(project.getPublishedBy())
                    .publisherUsername(publisher == null ? null : publisher.getUsername())
                    .publishedAt(project.getPublishedAt())
                    .publishedByAdmin(Boolean.TRUE.equals(project.getPublishedByAdmin()))
                    .assetCount(counts.getOrDefault(project.getId(), 0L))
                    .myRequestStatus(requestStatus)
                    .usable(usable)
                    // 2x#4：摘要补项目媒体类型（选择器按图片/视频过滤公共池项目）
                    .mediaTypes(project.getMediaTypes())
                    .build();
        }).toList();
    }

    private AssetProject loadProject(Long projectId) {
        AssetProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return project;
    }
}
