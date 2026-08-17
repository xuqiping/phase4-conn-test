package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.asset.dto.MediaTypeDef;
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
    /** 默认媒体类型受控词汇六项（V60 §C1b + S17 分镜，{key,category}）。 */
    public static final List<MediaTypeDef> DEFAULT_MEDIA_TYPES = List.of(
            new MediaTypeDef(Asset.MEDIA_PROMPT, Asset.CATEGORY_TEXT),
            new MediaTypeDef(Asset.MEDIA_SCRIPT, Asset.CATEGORY_TEXT),
            new MediaTypeDef(Asset.MEDIA_STORYBOARD, Asset.CATEGORY_TEXT),
            new MediaTypeDef(Asset.MEDIA_IMAGE, Asset.CATEGORY_IMAGE),
            new MediaTypeDef(Asset.MEDIA_VIDEO, Asset.CATEGORY_VIDEO),
            new MediaTypeDef(Asset.MEDIA_AUDIO, Asset.CATEGORY_AUDIO));
    /** 合法处理类别（系统固定四类）。 */
    private static final Set<String> VALID_CATEGORIES = Set.of(
            Asset.CATEGORY_TEXT, Asset.CATEGORY_IMAGE, Asset.CATEGORY_VIDEO, Asset.CATEGORY_AUDIO);
    private static final int NAME_MAX = 100;
    private static final int MEDIA_TYPE_KEY_MAX = 32;

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
        p.setMediaTypes(serializeMediaTypes(DEFAULT_MEDIA_TYPES));
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
        if (req.getMediaTypes() != null) {
            List<MediaTypeDef> newTypes = normalizeMediaTypes(req.getMediaTypes());
            reassignOnRemovedMediaTypes(projectId, parseMediaTypes(p.getMediaTypes()), newTypes, userId);
            p.setMediaTypes(serializeMediaTypes(newTypes));
        }
        projectMapper.updateById(p);
        log.info("asset project updated: id={} userId={} role={}", projectId, userId, role);
        return toVO(p, role);
    }

    /**
     * 项目设置（2x第三轮C6，决策 D1）：成员打分开关 + 内容模式。仅 OWNER（requireManage）。
     *
     * <p>局部更新：null 不改。contentMode 受控 SHARED/PERSONAL（切 PERSONAL 二次确认由前端弹窗承担，
     * 后端直接生效可逆——切回 SHARED 即恢复全员可编辑，不删数据）。
     * 切换即时生效：requireAssetOperate 每次操作实时读 contentMode。
     */
    @Transactional
    public ProjectVO updateSettings(Long projectId, Long userId, boolean admin,
                                    com.superprogrammer.asset.dto.ProjectSettingsRequest req) {
        AssetRole role = aclService.requireManage(projectId, userId, admin);
        AssetProject p = loadProject(projectId);
        boolean changed = false;
        if (req.getMemberScoringEnabled() != null) {
            p.setMemberScoringEnabled(req.getMemberScoringEnabled());
            changed = true;
        }
        if (req.getContentMode() != null) {
            String mode = req.getContentMode().trim();
            if (!AssetProject.CONTENT_MODE_SHARED.equals(mode)
                    && !AssetProject.CONTENT_MODE_PERSONAL.equals(mode)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "内容模式须为 " + AssetProject.CONTENT_MODE_SHARED + " 或 " + AssetProject.CONTENT_MODE_PERSONAL);
            }
            p.setContentMode(mode);
            changed = true;
        }
        if (changed) {
            projectMapper.updateById(p);
            log.info("asset project settings updated: id={} userId={} memberScoringEnabled={} contentMode={}",
                    projectId, userId, p.getMemberScoringEnabled(), p.getContentMode());
        }
        return toVO(p, role);
    }

    /**
     * 确保项目受控词汇含指定媒体类型（缺则 append），供一键分镜自动补「分镜」type（S19 L13 边界）。
     *
     * <p>幂等：已存在不改动。复用 {@link #normalizeMediaTypes} 校验 + {@link #serializeMediaTypes} 持久化。
     * V62 已 seed 存量项目，此为运行时兜底（自定义 vocab 删分镜后再一键分镜）。
     */
    @Transactional
    public void ensureMediaType(Long projectId, String key, String category) {
        AssetProject p = loadProject(projectId);
        List<MediaTypeDef> types = new ArrayList<>(parseMediaTypes(p.getMediaTypes()));
        boolean exists = types.stream().anyMatch(t -> key.equals(t.getKey()));
        if (exists) {
            return;
        }
        types.add(new MediaTypeDef(key, category));
        p.setMediaTypes(serializeMediaTypes(normalizeMediaTypes(types)));
        projectMapper.updateById(p);
        log.info("media type auto-appended: projectId={} key={} category={}", projectId, key, category);
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

    // ---------- L10'：删媒体类型联动（V60 §C1b） ----------

    /**
     * 移除媒体类型时，该 type 下资产改挂到同 category 的另一保留 type（L10'，对称 L10）。
     * 同 category 无其他 type 可迁移时阻删（防资产悬挂，plan 技术坑点）。
     */
    private void reassignOnRemovedMediaTypes(Long projectId, List<MediaTypeDef> oldTypes,
                                             List<MediaTypeDef> newTypes, Long userId) {
        Set<String> newKeys = new LinkedHashSet<>();
        for (MediaTypeDef t : newTypes) {
            newKeys.add(t.getKey());
        }
        for (MediaTypeDef old : oldTypes) {
            if (newKeys.contains(old.getKey())) {
                continue;
            }
            long count = assetMapper.countByMediaType(projectId, old.getKey());
            if (count == 0) {
                continue;
            }
            // 同 category 首个保留 type（保序）
            String fallback = newTypes.stream()
                    .filter(t -> t.getCategory().equals(old.getCategory()))
                    .map(MediaTypeDef::getKey)
                    .findFirst().orElse(null);
            if (fallback == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "媒体类型「" + old.getKey() + "」下仍有 " + count
                                + " 个资产，且同类别（" + old.getCategory() + "）无其他类型可迁移；请先迁移资产或保留至少一个该类别类型");
            }
            int moved = assetMapper.reassignMediaType(projectId, old.getKey(), fallback, userId);
            log.info("media type removed: projectId={} type={} category={} moved={} fallback={}",
                    projectId, old.getKey(), old.getCategory(), moved, fallback);
        }
    }

    /** 规范化媒体类型受控词汇：trim key、校验 category、去重保序、非空。 */
    private List<MediaTypeDef> normalizeMediaTypes(List<MediaTypeDef> types) {
        if (types == null || types.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "媒体类型受控词汇不可为空");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<MediaTypeDef> out = new ArrayList<>();
        for (MediaTypeDef t : types) {
            if (t == null) continue;
            String key = t.getKey() == null ? "" : t.getKey().trim();
            String category = t.getCategory() == null ? "" : t.getCategory().trim().toUpperCase();
            if (key.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "媒体类型 key 不能为空");
            }
            if (key.length() > MEDIA_TYPE_KEY_MAX) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "媒体类型 key 不得超过 " + MEDIA_TYPE_KEY_MAX + " 字");
            }
            if (!VALID_CATEGORIES.contains(category)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "媒体类型「" + key + "」的处理类别非法：" + category + "（应为 TEXT/IMAGE/VIDEO/AUDIO）");
            }
            if (seen.add(key)) {
                out.add(new MediaTypeDef(key, category));
            }
        }
        if (out.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "媒体类型受控词汇不可为空");
        }
        return out;
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

    private List<MediaTypeDef> parseMediaTypes(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>(DEFAULT_MEDIA_TYPES);
        }
        try {
            List<MediaTypeDef> types = objectMapper.readValue(json, new TypeReference<List<MediaTypeDef>>() {});
            return types == null || types.isEmpty() ? new ArrayList<>(DEFAULT_MEDIA_TYPES) : types;
        } catch (Exception e) {
            log.warn("parse mediaTypes failed, fallback default: {}", e.getMessage());
            return new ArrayList<>(DEFAULT_MEDIA_TYPES);
        }
    }

    private String serializeMediaTypes(List<MediaTypeDef> types) {
        try {
            return objectMapper.writeValueAsString(types);
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
                .mediaTypes(Collections.unmodifiableList(parseMediaTypes(p.getMediaTypes())))
                .role(role)
                .publicPool(Boolean.TRUE.equals(p.getPublicPool()))
                .publicAccessMode(p.getPublicAccessMode())
                .publishedBy(p.getPublishedBy())
                .publishedAt(p.getPublishedAt())
                .publishedByAdmin(Boolean.TRUE.equals(p.getPublishedByAdmin()))
                // 2x 待决策项（V100）：null 视为 TRUE（迁移 DEFAULT，存量行已回填）
                .allowPublicCopy(!Boolean.FALSE.equals(p.getAllowPublicCopy()))
                .memberScoringEnabled(Boolean.TRUE.equals(p.getMemberScoringEnabled()))
                .contentMode(p.getContentMode())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
