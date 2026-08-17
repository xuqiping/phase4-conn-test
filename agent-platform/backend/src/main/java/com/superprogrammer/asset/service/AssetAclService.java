package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetProjectMember;
import com.superprogrammer.asset.entity.AssetPublicAccessRequest;
import com.superprogrammer.asset.enums.AssetRole;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetProjectMemberMapper;
import com.superprogrammer.asset.mapper.AssetPublicAccessRequestMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 项目资产库·权限咽喉点（设计方案 §九 9.2「归属咽喉点」）。
 *
 * <p>所有项目级端点的权限咽喉：{@link #loadAccessible(Long, Long, boolean)} 三判——
 * <ol>
 *   <li>admin → 旁路（返 OWNER 级全权）</li>
 *   <li>{@code project.ownerId == userId} → OWNER</li>
 *   <li>asset_project_members 查到 → 其角色（EDITOR/VIEWER）</li>
 *   <li>否则 → 抛 FORBIDDEN（离开授权即失访，L1）</li>
 * </ol>
 *
 * <p>同 CanvasService.loadOwned 范式，但项目级授权是多对多成员模型而非单 owner。
 * 双层授权第一层（asset:write 平台权限）由控制器 @RequirePermission 兜底，本服务只判第二层（项目数据权限）。
 *
 * <p>可观测性：拒绝访问打日志（projectId/userId），复用 media traceId 风格。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetAclService {

    private final AssetProjectMapper projectMapper;
    private final AssetProjectMemberMapper memberMapper;
    private final AssetPublicAccessRequestMapper publicRequestMapper;

    /**
     * 载入项目并校验访问权（admin 旁路）。所有项目级端点的咽喉点。
     *
     * @param projectId 项目 id
     * @param userId    当前用户 id
     * @param admin     是否平台 admin（旁路全量）
     * @return 该用户在本项目的角色
     * @throws BusinessException 项目不存在 → NOT_FOUND；无权 → FORBIDDEN
     */
    public AssetRole loadAccessible(Long projectId, Long userId, boolean admin) {
        AssetProject project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return resolveRole(project, userId, admin);
    }

    /**
     * 角色三判（admin → OWNER 旁路；project.ownerId → OWNER；成员行 → 其角色；公众池 → VIEWER 只读）。
     * 从 {@link #loadAccessible} 抽出：{@link #requireAssetOperate} 需已载入的 project 再判一次角色，
     * 避免「先 loadAccessible 再 selectById」同一项目查两遍。
     */
    private AssetRole resolveRole(AssetProject project, Long userId, boolean admin) {
        // admin 旁路（全权，等同 owner）
        if (admin) {
            return AssetRole.OWNER;
        }
        // 判 owner
        if (userId != null && userId.equals(project.getOwnerId())) {
            return AssetRole.OWNER;
        }
        // 判 member
        AssetProjectMember member = memberMapper.selectOne(new LambdaQueryWrapper<AssetProjectMember>()
                .eq(AssetProjectMember::getProjectId, project.getId())
                .eq(AssetProjectMember::getUserId, userId));
        if (member != null) {
            return AssetRole.fromMemberRole(member.getRole());
        }
        // 公众池授权始终降级为只读 VIEWER，不进入成员表，也不能获得写权限。
        if (Boolean.TRUE.equals(project.getPublicPool())) {
            if (AssetProject.PUBLIC_ACCESS_OPEN.equals(project.getPublicAccessMode())) {
                return AssetRole.VIEWER;
            }
            if (AssetProject.PUBLIC_ACCESS_APPROVAL_REQUIRED.equals(project.getPublicAccessMode())) {
                AssetPublicAccessRequest request = publicRequestMapper.selectOne(
                        new LambdaQueryWrapper<AssetPublicAccessRequest>()
                                .eq(AssetPublicAccessRequest::getProjectId, project.getId())
                                .eq(AssetPublicAccessRequest::getApplicantId, userId)
                                .eq(AssetPublicAccessRequest::getStatus,
                                        AssetPublicAccessRequest.STATUS_APPROVED));
                if (request != null) {
                    return AssetRole.VIEWER;
                }
            }
        }
        log.warn("asset access denied: projectId={} userId={}", project.getId(), userId);
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该项目");
    }

    /**
     * 要求写权限（上传/编辑/入库/删/定稿/归档/维护词汇）。无权抛 FORBIDDEN。
     * viewer 写操作 403（安全清单）。
     */
    public AssetRole requireWrite(Long projectId, Long userId, boolean admin) {
        AssetRole role = loadAccessible(projectId, userId, admin);
        if (!role.canWrite()) {
            log.warn("asset write denied: projectId={} userId={} role={}", projectId, userId, role);
            throw new BusinessException(ErrorCode.FORBIDDEN, "该项目需要编辑权限");
        }
        return role;
    }

    /**
     * 真实项目关系判定（2x 待决策项 V100）：admin / OWNER / 成员表在册。
     * 公共池 VIEWER 与成员 VIEWER 同为 AssetRole.VIEWER，无法凭角色区分——
     * copy 管控（allow_public_copy）只约束公共 VIEWER，须用本方法判「非成员」。
     */
    public boolean isMemberOrOwner(AssetProject project, Long userId, boolean admin) {
        if (admin) {
            return true;
        }
        if (userId != null && userId.equals(project.getOwnerId())) {
            return true;
        }
        return memberMapper.selectOne(new LambdaQueryWrapper<AssetProjectMember>()
                .eq(AssetProjectMember::getProjectId, project.getId())
                .eq(AssetProjectMember::getUserId, userId)) != null;
    }

    /**
     * 要求管理权限（成员管理/转让/删项目）。无权抛 FORBIDDEN。仅 owner（admin 旁路）。
     */
    public AssetRole requireManage(Long projectId, Long userId, boolean admin) {
        AssetRole role = loadAccessible(projectId, userId, admin);
        if (!role.canManage()) {
            log.warn("asset manage denied: projectId={} userId={} role={}", projectId, userId, role);
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅项目所有者可执行此操作");
        }
        return role;
    }

    /**
     * 资产级写操作咽喉（2x第三轮C6，决策 D1）——requireWrite 语义之上叠加 PERSONAL 内容模式：
     * <ul>
     *   <li>VIEWER / 未授权 → FORBIDDEN（沿 requireWrite，写操作一律拒）</li>
     *   <li>OWNER / admin → 放行（不受 PERSONAL 约束）</li>
     *   <li>EDITOR + SHARED → 放行（存量行为零回归）</li>
     *   <li>EDITOR + PERSONAL → 仅 {@code asset.createdBy == userId}，否则 FORBIDDEN</li>
     * </ul>
     *
     * <p>update/delete/lock/unlock/archive/unarchive/新版本/一致性包保存 统一走此方法（集中一处防漏点）。
     * createdBy 为 NULL 的存量资产（回填兜底后理论不存在；新路径 C6 起显式写入）在 PERSONAL 下对
     * EDITOR 视为他人内容——fail-closed，OWNER 仍可管理。
     */
    public AssetRole requireAssetOperate(Asset asset, Long userId, boolean admin) {
        AssetProject project = projectMapper.selectById(asset.getProjectId());
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        AssetRole role = resolveRole(project, userId, admin);
        if (!role.canWrite()) {
            log.warn("asset operate denied: projectId={} userId={} role={}", asset.getProjectId(), userId, role);
            throw new BusinessException(ErrorCode.FORBIDDEN, "该项目需要编辑权限");
        }
        if (!role.canManage()
                && AssetProject.CONTENT_MODE_PERSONAL.equals(project.getContentMode())
                && !Objects.equals(userId, asset.getCreatedBy())) {
            log.warn("asset operate denied (PERSONAL): assetId={} userId={} creator={}",
                    asset.getId(), userId, asset.getCreatedBy());
            throw new BusinessException(ErrorCode.FORBIDDEN, "个人内容模式下仅能管理自己上传的内容");
        }
        return role;
    }
}
