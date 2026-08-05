package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.asset.dto.AssetCreateRequest;
import com.superprogrammer.asset.dto.AssetUpdateRequest;
import com.superprogrammer.asset.dto.AssetVO;
import com.superprogrammer.asset.dto.MatrixCountVO;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetRoleLink;
import com.superprogrammer.asset.entity.AssetVersion;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetRoleLinkMapper;
import com.superprogrammer.asset.mapper.AssetVersionMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.common.result.PageResult;
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
 * 项目资产库·资产 CRUD + 矩阵筛选/搜索 + 计数聚合（plan §S4 / FR-003/004/005，设计方案 §二/§三/§九）。
 *
 * <p>双轴矩阵筛选：轴A=mediaType（顶 Tab），轴B=role（左栏，走 role_links 关系表不查 JSONB）。
 * 搜索：项目内 name/description LIKE（q≤50；中文 LIKE 项目内数据量小可接受，后续 pg_trgm）。
 *
 * <p>性能（plan 坑点预判）：
 * <ul>
 *   <li>role_links 批查组装（IN）防 N+1</li>
 *   <li>计数单条 GROUP BY 聚合返全图（每格徽标）</li>
 *   <li>列表只 select meta，content 按需单取</li>
 * </ul>
 *
 * <p>权限：写操作 requireWrite（editor+owner），读 loadAccessible。viewer 可读/不可写。
 *
 * <p>可观测性：建/改/删打日志（assetId/projectId/userId）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private static final int NAME_MAX = 100;
    private static final int Q_MAX = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final AssetMapper assetMapper;
    private final AssetVersionMapper versionMapper;
    private final AssetRoleLinkMapper roleLinkMapper;
    private final AssetProjectMapper projectMapper;
    private final AssetAclService aclService;
    private final ObjectMapper objectMapper;

    /** 新建文本类资产（PROMPT/SCRIPT）+ 版本 1。文件类经上传端点。 */
    @Transactional
    public AssetVO create(Long projectId, Long userId, boolean admin, AssetCreateRequest req) {
        aclService.requireWrite(projectId, userId, admin);
        validateMediaType(req.getMediaType(), true);
        String name = validateName(req.getName());
        Asset asset = new Asset();
        asset.setProjectId(projectId);
        asset.setMediaType(req.getMediaType());
        asset.setName(name);
        asset.setDescription(req.getDescription());
        asset.setStatus(Asset.STATUS_DRAFT);
        asset.setTags(serializeList(req.getTags()));
        asset.setCurrentVersion(1);
        // 文本类正文必填（content JSON）
        String content = req.getContent();
        if (isTextType(req.getMediaType())) {
            if (content == null || content.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文本类资产正文不能为空");
            }
            asset.setContent(content);
        } else {
            asset.setContent("{}");
        }
        asset.setGenMeta("{}");
        assetMapper.insert(asset);
        // 版本 1
        AssetVersion v1 = new AssetVersion();
        v1.setAssetId(asset.getId());
        v1.setVersion(1);
        v1.setContent(asset.getContent());
        versionMapper.insert(v1);
        // 角色挂载（受控词汇校验）
        syncRoleLinks(projectId, asset.getId(), req.getRoleKeys());
        log.info("asset created: id={} projectId={} mediaType={} userId={}", asset.getId(), projectId, req.getMediaType(), userId);
        return toVO(asset, false);
    }

    /**
     * 矩阵筛选/搜索列表（分页）。loadAccessible（viewer 可读）。
     * 默认隐藏 ARCHIVED（L3），status=ARCHIVED 显式查时可及。
     */
    public PageResult<AssetVO> list(Long projectId, Long userId, boolean admin,
                                    String mediaType, String role, String q, String status,
                                    int page, int size) {
        aclService.loadAccessible(projectId, userId, admin);
        LambdaQueryWrapper<Asset> w = new LambdaQueryWrapper<>();
        w.eq(Asset::getProjectId, projectId);
        if (mediaType != null && !mediaType.isBlank()) {
            validateMediaType(mediaType, false);
            w.eq(Asset::getMediaType, mediaType);
        }
        if (status != null && !status.isBlank()) {
            w.eq(Asset::getStatus, status);
        } else {
            // 默认隐藏归档（L3）
            w.ne(Asset::getStatus, Asset.STATUS_ARCHIVED);
        }
        if (q != null && !q.isBlank()) {
            String kw = q.trim();
            if (kw.length() > Q_MAX) {
                kw = kw.substring(0, Q_MAX);
            }
            final String keyword = kw;
            w.and(qw -> qw.like(Asset::getName, keyword).or().like(Asset::getDescription, keyword));
        }
        // 角色过滤：走 role_links 关系表（不查 JSONB）
        if (role != null && !role.isBlank()) {
            List<Long> ids = roleLinkMapper.selectList(new LambdaQueryWrapper<AssetRoleLink>()
                            .eq(AssetRoleLink::getRoleKey, role))
                    .stream().map(AssetRoleLink::getAssetId).collect(Collectors.toList());
            if (ids.isEmpty()) {
                return PageResult.of(Collections.emptyList(), 0L, page, size);
            }
            w.in(Asset::getId, ids);
        }
        w.orderByDesc(Asset::getUpdatedAt);
        IPage<Asset> p = assetMapper.selectPage(new Page<>(page, size <= 0 ? DEFAULT_PAGE_SIZE : size), w);
        List<AssetVO> vos = assembleRoles(p.getRecords(), false);
        return PageResult.of(vos, p.getTotal(), (int) p.getCurrent(), (int) p.getSize());
    }

    /** 矩阵每格计数（单条 GROUP BY 聚合，防 N+1，plan 坑点预判）。loadAccessible。 */
    public MatrixCountVO countMatrix(Long projectId, Long userId, boolean admin) {
        aclService.loadAccessible(projectId, userId, admin);
        return new MatrixCountVO(assetMapper.countMatrixByRole(projectId), assetMapper.countByType(projectId));
    }

    /** 详情（带 content + 角色 + 当前版本 file_id）。loadAccessible。 */
    public AssetVO get(Long assetId, Long userId, boolean admin) {
        Asset asset = loadAsset(assetId);
        aclService.loadAccessible(asset.getProjectId(), userId, admin);
        AssetVO vo = toVO(asset, true);
        vo.setRoleKeys(rolesOf(List.of(assetId)).getOrDefault(assetId, Collections.emptyList()));
        // 当前版本 file_id
        AssetVersion cur = versionMapper.selectOne(new LambdaQueryWrapper<AssetVersion>()
                .eq(AssetVersion::getAssetId, assetId)
                .eq(AssetVersion::getVersion, asset.getCurrentVersion()));
        if (cur != null) {
            vo.setFileId(cur.getFileId());
        }
        return vo;
    }

    /** 更新 meta + 分类（正文改版走版本端点 S5）。requireWrite。 */
    @Transactional
    public AssetVO update(Long assetId, Long userId, boolean admin, AssetUpdateRequest req) {
        Asset asset = loadAsset(assetId);
        aclService.requireWrite(asset.getProjectId(), userId, admin);
        if (req.getName() != null) {
            asset.setName(validateName(req.getName()));
        }
        if (req.getDescription() != null) {
            asset.setDescription(req.getDescription());
        }
        if (req.getTags() != null) {
            asset.setTags(serializeList(req.getTags()));
        }
        assetMapper.updateById(asset);
        if (req.getRoleKeys() != null) {
            syncRoleLinks(asset.getProjectId(), assetId, req.getRoleKeys());
        }
        log.info("asset updated: id={} projectId={} userId={}", assetId, asset.getProjectId(), userId);
        return toVO(asset, false);
    }

    /** 软删（requireWrite）。role_links 硬删；bindings 留存历史（引用快照语义）。 */
    @Transactional
    public void delete(Long assetId, Long userId, boolean admin) {
        Asset asset = loadAsset(assetId);
        aclService.requireWrite(asset.getProjectId(), userId, admin);
        assetMapper.deleteById(assetId);
        roleLinkMapper.delete(new LambdaQueryWrapper<AssetRoleLink>().eq(AssetRoleLink::getAssetId, assetId));
        log.info("asset deleted: id={} projectId={} userId={}", assetId, asset.getProjectId(), userId);
    }

    // ---------- 角色挂载同步 ----------

    /** 同步资产角色挂载（受控词汇校验：roleKey 须在项目 narrative_roles 内）。 */
    private void syncRoleLinks(Long projectId, Long assetId, List<String> roleKeys) {
        // 先清旧
        roleLinkMapper.delete(new LambdaQueryWrapper<AssetRoleLink>().eq(AssetRoleLink::getAssetId, assetId));
        if (roleKeys == null || roleKeys.isEmpty()) {
            return;
        }
        // 受控词汇校验
        Set<String> vocab = new LinkedHashSet<>(loadNarrativeRoles(projectId));
        Set<String> seen = new LinkedHashSet<>();
        for (String key : roleKeys) {
            if (key == null || key.isBlank()) continue;
            String k = key.trim();
            if (!vocab.contains(k)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "叙事角色「" + k + "」不在项目受控词汇内");
            }
            if (seen.add(k)) {
                AssetRoleLink link = new AssetRoleLink();
                link.setAssetId(assetId);
                link.setRoleKey(k);
                roleLinkMapper.insert(link);
            }
        }
    }

    // ---------- 批查组装（防 N+1） ----------

    /** 列表批量组装角色（单次 IN 查询，内存分组）。 */
    private List<AssetVO> assembleRoles(List<Asset> assets, boolean withContent) {
        if (assets.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = assets.stream().map(Asset::getId).collect(Collectors.toList());
        Map<Long, List<String>> roleMap = rolesOf(ids);
        return assets.stream().map(a -> {
            AssetVO vo = toVO(a, withContent);
            vo.setRoleKeys(roleMap.getOrDefault(a.getId(), Collections.emptyList()));
            return vo;
        }).collect(Collectors.toList());
    }

    /** 批量查角色：单次 IN，内存分组（防 N+1，plan 坑点预判）。 */
    private Map<Long, List<String>> rolesOf(List<Long> assetIds) {
        if (assetIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return roleLinkMapper.selectList(new LambdaQueryWrapper<AssetRoleLink>()
                        .in(AssetRoleLink::getAssetId, assetIds))
                .stream().collect(Collectors.groupingBy(AssetRoleLink::getAssetId,
                        Collectors.mapping(AssetRoleLink::getRoleKey, Collectors.toList())));
    }

    // ---------- 校验/序列化/VO ----------

    private void validateMediaType(String mediaType, boolean forCreate) {
        if (mediaType == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内容类型不能为空");
        }
        if (!isTextType(mediaType) && !isFileType(mediaType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "非法内容类型：" + mediaType);
        }
    }

    private boolean isTextType(String mediaType) {
        return Asset.MEDIA_PROMPT.equals(mediaType) || Asset.MEDIA_SCRIPT.equals(mediaType);
    }

    private boolean isFileType(String mediaType) {
        return Asset.MEDIA_IMAGE.equals(mediaType) || Asset.MEDIA_VIDEO.equals(mediaType) || Asset.MEDIA_AUDIO.equals(mediaType);
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "资产名不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > NAME_MAX) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "资产名不得超过 " + NAME_MAX + " 字");
        }
        return trimmed;
    }

    private List<String> loadNarrativeRoles(Long projectId) {
        AssetProject p = projectMapper.selectById(projectId);
        if (p == null || p.getNarrativeRoles() == null || p.getNarrativeRoles().isBlank()) {
            return new ArrayList<>(AssetProjectService.DEFAULT_NARRATIVE_ROLES);
        }
        try {
            List<String> roles = objectMapper.readValue(p.getNarrativeRoles(), new TypeReference<List<String>>() {});
            return roles == null ? new ArrayList<>(AssetProjectService.DEFAULT_NARRATIVE_ROLES) : roles;
        } catch (Exception e) {
            log.warn("parse narrativeRoles failed projectId={}: {}", projectId, e.getMessage());
            return new ArrayList<>(AssetProjectService.DEFAULT_NARRATIVE_ROLES);
        }
    }

    private String serializeList(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? Collections.emptyList() : list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Asset loadAsset(Long assetId) {
        Asset a = assetMapper.selectById(assetId);
        if (a == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        return a;
    }

    private AssetVO toVO(Asset a, boolean withContent) {
        List<String> tags = null;
        if (a.getTags() != null && !a.getTags().isBlank()) {
            try {
                tags = objectMapper.readValue(a.getTags(), new TypeReference<List<String>>() {});
            } catch (Exception ignored) {
                tags = Collections.emptyList();
            }
        }
        return AssetVO.builder()
                .id(a.getId())
                .projectId(a.getProjectId())
                .mediaType(a.getMediaType())
                .name(a.getName())
                .description(a.getDescription())
                .tags(tags)
                .status(a.getStatus())
                .content(withContent ? a.getContent() : null)
                .genMeta(a.getGenMeta())
                .currentVersion(a.getCurrentVersion())
                .createdBy(a.getCreatedBy())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
