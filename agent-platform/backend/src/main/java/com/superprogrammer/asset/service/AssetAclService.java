package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetProjectMember;
import com.superprogrammer.asset.enums.AssetRole;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetProjectMemberMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
                .eq(AssetProjectMember::getProjectId, projectId)
                .eq(AssetProjectMember::getUserId, userId));
        if (member != null) {
            return AssetRole.fromMemberRole(member.getRole());
        }
        log.warn("asset access denied: projectId={} userId={}", projectId, userId);
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
}
