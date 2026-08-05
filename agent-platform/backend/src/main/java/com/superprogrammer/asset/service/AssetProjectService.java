package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.asset.dto.ProjectCreateRequest;
import com.superprogrammer.asset.dto.ProjectUpdateRequest;
import com.superprogrammer.asset.dto.ProjectVO;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetBinding;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetProjectMember;
import com.superprogrammer.asset.entity.AssetRoleLink;
import com.superprogrammer.asset.enums.AssetRole;
import com.superprogrammer.asset.mapper.AssetBindingMapper;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetProjectMemberMapper;
import com.superprogrammer.asset.mapper.AssetRoleLinkMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目资产库·项目 CRUD + 受控词汇维护（plan §S2 / FR-001，设计方案 §二/§九）。
 *
 * <p>列表两视图：{@link #list} 返回当前用户可访问的全部项目（owner + member），各带 {@link AssetRole}，
 * 前端按 role 分「我的项目 / 共享给我」Tab。
 *
 * <p>联动：
 * <ul>
 *   <li>L4 删项目：级联软删项目内资产/成员/绑定（owner only，requireManage）</li>
 *   <li>L10 删叙事角色桶：挂该桶的资产自动归「通用」（narrativeRoles 更新时）</li>
 * </ul>
 *
 * <p>可观测性：建/改/删打日志（projectId/userId），复用 media traceId 风格。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetProjectService {

    /** 默认叙事角色五桶（设计方案 §二，受控词汇防标签腐烂）。 */
    public static final List<String> DEFAULT_NARRATIVE_ROLES = List.of("人物", "道具", "场景", "风格", "通用");
    /** 兜底桶（L10 删桶时资产归入此桶）。 */
    public static final String FALLBACK_ROLE = "通用";
    private static final int NAME_MAX = 100;

    private final AssetProjectMapper projectMapper;
    private final AssetProjectMemberMapper memberMapper;
    private final AssetMapper assetMapper;
    private final AssetRoleLinkMapper roleLinkMapper;
    private final AssetBindingMapper bindingMapper;
    private final AssetAclService aclService;
    private final ObjectMapper objectMapper;

    /** 新建项目（owner=当前用户，narrative_roles 默认五桶）。 */
    public ProjectVO create(Long userId, ProjectCreateRequest req) {
        String name = validateName(req.getName());
        AssetProject p = new AssetProject();
        p.setOwnerId(userId);
        p.setName(name);
        p.setDescription(req.getDescription());
        p.setNarrativeRoles(serializeRoles(DEFAULT_NARRATIVE_ROLES));
        projectMapper.insert(p);
        log.info("asset project created: id={} ownerId={} name={}", p.getId(), userId, name);
        return toVO(p, AssetRole.OWNER);
    }

    /**
     * 列表（两视图合并）：owner 项目 + member 项目，各带当前用户角色。admin 看全量。
     * 前端按 role 分 Tab（设计方案 §十）。
     */
    public List<ProjectVO> list(Long userId, boolean admin) {
        // admin 旁路：全量，角色标记 OWNER（全权）
        if (admin) {
            return projectMapper.selectList(new LambdaQueryWrapper<AssetProject>()
                            .eq(AssetProject::getDeleted, 0)
                            .orderByDesc(AssetProject::getUpdatedAt)).stream()
                    .map(p -> toVO(p, AssetRole.OWNER))
                    .collect(Collectors.toList());
        }
        // owner 项目
        List<AssetProject> owned = projectMapper.selectList(new LambdaQueryWrapper<AssetProject>()
                .eq(AssetProject::getOwnerId, userId)
                .eq(AssetProject::getDeleted, 0)
                .orderByDesc(AssetProject::getUpdatedAt));
        // member 项目（我被人授权的）
        List<AssetProjectMember> myMembers = memberMapper.selectList(new LambdaQueryWrapper<AssetProjectMember>()
                .eq(AssetProjectMember::getUserId, userId)
                .eq(AssetProjectMember::getDeleted, 0));
        Map<Long, String> memberRoleByProject = myMembers.stream()
                .collect(Collectors.toMap(AssetProjectMember::getProjectId, AssetProjectMember::getRole, (a, b) -> a));

        List<ProjectVO> result = new ArrayList<>();
        owned.forEach(p -> result.add(toVO(p, AssetRole.OWNER)));
        if (!memberRoleByProject.isEmpty()) {
            List<AssetProject> shared = projectMapper.selectList(new LambdaQueryWrapper<AssetProject>()
                    .in(AssetProject::getId, memberRoleByProject.keySet())
                    .eq(AssetProject::getDeleted, 0)
                    .orderByDesc(AssetProject::getUpdatedAt));
            shared.forEach(p -> result.add(toVO(p, AssetRole.fromMemberRole(memberRoleByProject.get(p.getId())))));
        }
        return result;
    }

    /** 详情（带 narrative_roles）。 */
    public ProjectVO get(Long projectId, Long userId, boolean admin) {
        AssetRole role = aclService.loadAccessible(projectId, userId, admin);
        return toVO(loadProject(projectId), role);
    }

    /**
     * 更新（name/description/cover/narrativeRoles）。editor+（requireWrite）。
     * narrativeRoles 维护受控词汇；移除桶触发 L10（资产归「通用」）。
     */
    @Transactional
    public ProjectVO update(Long projectId, Long userId, boolean admin, ProjectUpdateRequest req) {
        AssetRole role = aclService.requireWrite(projectId, userId, admin);
        AssetProject p = loadProject(projectId);

        if (req.getName() != null) {
            p.setName(validateName(req.getName()));
        }
        if (req.getDescription() != null) {
            p.setDescription(req.getDescription());
        }
        if (req.getCoverFileId() != null) {
            p.setCoverFileId(req.getCoverFileId());
        }
        if (req.getNarrativeRoles() != null) {
            List<String> newRoles = normalizeRoles(req.getNarrativeRoles());
            reassignOnRemovedRoles(projectId, parseRoles(p.getNarrativeRoles()), newRoles);
            p.setNarrativeRoles(serializeRoles(newRoles));
        }
        projectMapper.updateById(p);
        log.info("asset project updated: id={} userId={} role={}", projectId, userId, role);
        return toVO(p, role);
    }

    /**
     * 删项目（级联软删资产/成员/绑定，L4）。仅 owner（requireManage）。
     * stored_files 文件保留（历史/复用）；画布引用快照不受影响（版本快照语义属引用方）。
     */
    @Transactional
    public void delete(Long projectId, Long userId, boolean admin) {
        aclService.requireManage(projectId, userId, admin);
        // 项目内资产 ids（含已软删一并清理绑定）
        List<Long> assetIds = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getProjectId, projectId)
                        .select(Asset::getId))
                .stream().map(Asset::getId).collect(Collectors.toList());
        if (!assetIds.isEmpty()) {
            // 软删绑定（按资产）
            bindingMapper.delete(new LambdaQueryWrapper<AssetBinding>().in(AssetBinding::getAssetId, assetIds));
            // 软删角色关联
            roleLinkMapper.delete(new LambdaQueryWrapper<AssetRoleLink>().in(AssetRoleLink::getAssetId, assetIds));
        }
        // 软删资产
        assetMapper.delete(new LambdaQueryWrapper<Asset>().eq(Asset::getProjectId, projectId));
        // 软删成员
        memberMapper.delete(new LambdaQueryWrapper<AssetProjectMember>().eq(AssetProjectMember::getProjectId, projectId));
        // 软删项目
        projectMapper.deleteById(projectId);
        log.info("asset project deleted (cascade): id={} userId={} assetCount={}", projectId, userId, assetIds.size());
    }

    // ---------- L10：删叙事角色桶联动 ----------

    /**
     * 移除角色桶时，挂该桶的资产自动归「通用」（L10）。
     * 仅处理被移除的桶；保留桶不动。空桶直接删（无资产挂载时自然无操作）。
     */
    private void reassignOnRemovedRoles(Long projectId, List<String> oldRoles, List<String> newRoles) {
        Set<String> removed = new LinkedHashSet<>(oldRoles);
        removed.removeAll(newRoles);
        if (removed.isEmpty()) {
            return;
        }
        // 项目内资产 ids
        List<Long> assetIds = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getProjectId, projectId)
                        .eq(Asset::getDeleted, 0)
                        .select(Asset::getId))
                .stream().map(Asset::getId).collect(Collectors.toList());
        if (assetIds.isEmpty()) {
            return;
        }
        boolean fallbackExists = newRoles.contains(FALLBACK_ROLE);
        for (String removedKey : removed) {
            // 找挂了被删桶的资产
            List<AssetRoleLink> links = roleLinkMapper.selectList(new LambdaQueryWrapper<AssetRoleLink>()
                    .in(AssetRoleLink::getAssetId, assetIds)
                    .eq(AssetRoleLink::getRoleKey, removedKey));
            if (links.isEmpty()) {
                continue;
            }
            // 删除这些 link
            roleLinkMapper.delete(new LambdaQueryWrapper<AssetRoleLink>()
                    .in(AssetRoleLink::getAssetId, links.stream().map(AssetRoleLink::getAssetId).collect(Collectors.toList()))
                    .eq(AssetRoleLink::getRoleKey, removedKey));
            // 受影响资产归「通用」（仅当通用桶仍存在）
            if (fallbackExists) {
                for (AssetRoleLink link : links) {
                    ensureRoleLink(link.getAssetId(), FALLBACK_ROLE);
                }
            }
            log.info("narrative role removed: projectId={} role={} affectedAssets={} fallback={}",
                    projectId, removedKey, links.size(), fallbackExists);
        }
    }

    /** 确保资产挂了某角色（幂等，无则插）。 */
    private void ensureRoleLink(Long assetId, String roleKey) {
        Long exists = roleLinkMapper.selectCount(new LambdaQueryWrapper<AssetRoleLink>()
                .eq(AssetRoleLink::getAssetId, assetId)
                .eq(AssetRoleLink::getRoleKey, roleKey));
        if (exists == null || exists == 0) {
            AssetRoleLink link = new AssetRoleLink();
            link.setAssetId(assetId);
            link.setRoleKey(roleKey);
            roleLinkMapper.insert(link);
        }
    }

    // ---------- 校验/序列化/VO ----------

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "项目名不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > NAME_MAX) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "项目名不得超过 " + NAME_MAX + " 字");
        }
        return trimmed;
    }

    /** 规范化角色桶列表：去空白、去重、保序、非空。 */
    private List<String> normalizeRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "叙事角色桶不可为空");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String r : roles) {
            if (r != null && !r.isBlank()) {
                seen.add(r.trim());
            }
        }
        if (seen.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "叙事角色桶不可为空");
        }
        return new ArrayList<>(seen);
    }

    private List<String> parseRoles(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>(DEFAULT_NARRATIVE_ROLES);
        }
        try {
            List<String> roles = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return roles == null ? new ArrayList<>() : new ArrayList<>(roles);
        } catch (Exception e) {
            log.warn("parse narrativeRoles failed, fallback default: {}", e.getMessage());
            return new ArrayList<>(DEFAULT_NARRATIVE_ROLES);
        }
    }

    private String serializeRoles(List<String> roles) {
        try {
            return objectMapper.writeValueAsString(roles);
        } catch (Exception e) {
            return "[]";
        }
    }

    private AssetProject loadProject(Long projectId) {
        AssetProject p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return p;
    }

    private ProjectVO toVO(AssetProject p, AssetRole role) {
        return ProjectVO.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .coverFileId(p.getCoverFileId())
                .ownerId(p.getOwnerId())
                .narrativeRoles(Collections.unmodifiableList(parseRoles(p.getNarrativeRoles())))
                .role(role)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
