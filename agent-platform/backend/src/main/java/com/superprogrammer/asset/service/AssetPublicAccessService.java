package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.asset.dto.PublicAccessRequestVO;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetPublicAccessRequest;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetPublicAccessRequestMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** 审批型公众池项目的申请、决定与撤销状态机。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetPublicAccessService {

    private static final Set<String> DECISIONS = Set.of(
            AssetPublicAccessRequest.STATUS_APPROVED,
            AssetPublicAccessRequest.STATUS_REJECTED);

    private final AssetProjectMapper projectMapper;
    private final AssetPublicAccessRequestMapper requestMapper;
    private final AssetAclService aclService;

    @Transactional(rollbackFor = Exception.class)
    public PublicAccessRequestVO request(Long projectId, Long applicantId) {
        AssetProject project = requireApprovalProject(projectId);
        if (applicantId == null || applicantId.equals(project.getOwnerId())) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "项目所有者无需申请公共访问");
        }
        AssetPublicAccessRequest existing = requestMapper.selectForUpdate(projectId, applicantId);
        if (existing == null) {
            requestMapper.insertPendingIfAbsent(projectId, applicantId);
            existing = requestMapper.selectForUpdate(projectId, applicantId);
            if (existing == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "公共访问申请创建失败");
            }
            log.info("asset public access requested: projectId={} applicantId={}", projectId, applicantId);
        } else if (AssetPublicAccessRequest.STATUS_REJECTED.equals(existing.getStatus())
                || AssetPublicAccessRequest.STATUS_REVOKED.equals(existing.getStatus())) {
            if (requestMapper.resetToPending(existing.getId(), applicantId) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "申请状态已变化，请刷新后重试");
            }
            existing.setStatus(AssetPublicAccessRequest.STATUS_PENDING);
            existing.setDecidedBy(null);
            existing.setDecidedAt(null);
            log.info("asset public access reapplied: projectId={} applicantId={}", projectId, applicantId);
        }
        return toVO(existing);
    }

    public PublicAccessRequestVO myStatus(Long projectId, Long applicantId) {
        AssetPublicAccessRequest request = requestMapper.selectOne(
                new LambdaQueryWrapper<AssetPublicAccessRequest>()
                        .eq(AssetPublicAccessRequest::getProjectId, projectId)
                        .eq(AssetPublicAccessRequest::getApplicantId, applicantId));
        return request == null ? null : toVO(request);
    }

    public List<PublicAccessRequestVO> listForOwner(Long projectId, Long userId, boolean admin) {
        aclService.requireManage(projectId, userId, admin);
        requirePublished(projectId);
        return requestMapper.selectList(new LambdaQueryWrapper<AssetPublicAccessRequest>()
                        .eq(AssetPublicAccessRequest::getProjectId, projectId)
                        .orderByDesc(AssetPublicAccessRequest::getCreatedAt))
                .stream().map(AssetPublicAccessService::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void decide(Long projectId, Long requestId, Long userId, boolean admin, String decision) {
        requireApprovalProject(projectId);
        aclService.requireManage(projectId, userId, admin);
        if (!DECISIONS.contains(decision)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "决定必须为 APPROVED 或 REJECTED");
        }
        AssetPublicAccessRequest request = requireRequest(projectId, requestId);
        if (requestMapper.decidePending(request.getId(), decision, userId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请已被处理，请刷新后重试");
        }
        log.info("asset public access decided: projectId={} requestId={} decision={} userId={}",
                projectId, requestId, decision, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long projectId, Long requestId, Long userId, boolean admin) {
        requirePublished(projectId);
        aclService.requireManage(projectId, userId, admin);
        AssetPublicAccessRequest request = requireRequest(projectId, requestId);
        if (requestMapper.revokeApproved(request.getId(), userId) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅可撤销当前已批准的申请");
        }
        log.info("asset public access revoked: projectId={} requestId={} userId={}",
                projectId, requestId, userId);
    }

    private AssetProject requireApprovalProject(Long projectId) {
        AssetProject project = requirePublished(projectId);
        if (!AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED.equals(project.getPublicAccessMode())) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "直接开放项目无需申请");
        }
        return project;
    }

    private AssetProject requirePublished(Long projectId) {
        AssetProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        if (!Boolean.TRUE.equals(project.getPublicPool())) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "项目已移出公众池");
        }
        return project;
    }

    private AssetPublicAccessRequest requireRequest(Long projectId, Long requestId) {
        AssetPublicAccessRequest request = requestMapper.selectById(requestId);
        if (request == null || !projectId.equals(request.getProjectId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "访问申请不存在");
        }
        return request;
    }

    private static PublicAccessRequestVO toVO(AssetPublicAccessRequest request) {
        return PublicAccessRequestVO.builder()
                .id(request.getId())
                .projectId(request.getProjectId())
                .applicantId(request.getApplicantId())
                .status(request.getStatus())
                .decidedBy(request.getDecidedBy())
                .decidedAt(request.getDecidedAt())
                .createdAt(request.getCreatedAt())
                .updatedAt(request.getUpdatedAt())
                .build();
    }
}
