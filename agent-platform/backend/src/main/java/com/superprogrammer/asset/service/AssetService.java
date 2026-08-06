package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.superprogrammer.file.entity.StoredFileEntity;
import com.superprogrammer.file.service.FileStorageService;
import com.superprogrammer.file.service.StoredFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
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
    private final FileStorageService fileStorageService;
    private final AssetVersionService versionService;

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
        // 文本类正文必填 + 须为合法 JSON（content 列为 JSONB，防非 JSON 客户端直传撑爆 → 500）
        String content = req.getContent();
        if (isTextType(req.getMediaType())) {
            if (content == null || content.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文本类资产正文不能为空");
            }
            validateContentJson(content);
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
     * 文件类资产上传（图片/视频/音频，FR-004）。
     *
     * <p>落 {@code stored_files}(source={@code SOURCE_ASSET})，复用原 file_id 不复制文件；
     * 类型↔资产类型匹配校验（mp4 不可入图片资产，安全清单）；技术元数据提取入 gen_meta
     * （图片宽高用 JDK ImageIO 同步读；视频时长/分辨率懒提取，plan 坑点预判：大文件不阻塞上传）。
     */
    @Transactional
    public AssetVO upload(Long projectId, Long userId, boolean admin, MultipartFile file,
                          String mediaType, String name, String description, List<String> roleKeys) {
        aclService.requireWrite(projectId, userId, admin);
        validateMediaType(mediaType, false);
        if (!isFileType(mediaType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传仅支持图片/视频/音频类资产");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }
        validateFileMime(mediaType, file.getContentType());
        // 落盘 + 登记 owner（SOURCE_ASSET）
        StoredFile stored = fileStorageService.store(file, userId, StoredFileEntity.SOURCE_ASSET);
        // 名称：缺省用原始文件名
        String safeName = validateName((name == null || name.isBlank()) ? stored.name() : name);
        Asset asset = new Asset();
        asset.setProjectId(projectId);
        asset.setMediaType(mediaType);
        asset.setName(safeName);
        asset.setDescription(description);
        asset.setStatus(Asset.STATUS_DRAFT);
        asset.setTags("[]");
        asset.setContent("{}");
        asset.setCurrentVersion(1);
        asset.setGenMeta(buildUploadGenMeta(mediaType, stored, file));
        assetMapper.insert(asset);
        // 版本 1（带 file_id）
        AssetVersion v1 = new AssetVersion();
        v1.setAssetId(asset.getId());
        v1.setVersion(1);
        v1.setFileId(stored.fileId());
        v1.setContent("{}");
        versionMapper.insert(v1);
        // 角色挂载（受控词汇校验）
        syncRoleLinks(projectId, asset.getId(), roleKeys);
        log.info("asset uploaded: id={} projectId={} mediaType={} fileId={} userId={}",
                asset.getId(), projectId, mediaType, stored.fileId(), userId);
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

    // ---------- 状态机（plan §S5 / FR-006，设计方案 §六，L2/L3） ----------

    /**
     * 定稿：DRAFT→LOCKED（L2）。已 LOCKED 幂等返回；ARCHIVED 不可定稿（400）。
     * 定稿后被画布引用=锁版本快照（资产升级不影响已引用方，版本隔离防冲突）。
     */
    @Transactional
    public AssetVO lock(Long assetId, Long userId, boolean admin) {
        Asset asset = loadAsset(assetId);
        aclService.requireWrite(asset.getProjectId(), userId, admin);
        if (Asset.STATUS_LOCKED.equals(asset.getStatus())) {
            return toVO(asset, false);
        }
        if (!Asset.STATUS_DRAFT.equals(asset.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "归档资产不可定稿");
        }
        asset.setStatus(Asset.STATUS_LOCKED);
        assetMapper.updateById(asset);
        log.info("asset locked: id={} userId={}", assetId, userId);
        return toVO(asset, false);
    }

    /**
     * 解锁回退草稿：LOCKED→DRAFT（L2「解锁回退草稿可再改」）。已 DRAFT 幂等；ARCHIVED 400。
     */
    @Transactional
    public AssetVO unlock(Long assetId, Long userId, boolean admin) {
        Asset asset = loadAsset(assetId);
        aclService.requireWrite(asset.getProjectId(), userId, admin);
        if (Asset.STATUS_DRAFT.equals(asset.getStatus())) {
            return toVO(asset, false);
        }
        if (!Asset.STATUS_LOCKED.equals(asset.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅已定稿资产可解锁");
        }
        asset.setStatus(Asset.STATUS_DRAFT);
        assetMapper.updateById(asset);
        log.info("asset unlocked: id={} userId={}", assetId, userId);
        return toVO(asset, false);
    }

    /**
     * 归档（软删语义）：any→ARCHIVED（L3）。已 ARCHIVED 幂等。
     * 归档 = 不进默认列表但可检索（list 默认隐藏 ARCHIVED，status=ARCHIVED 可显式查）。
     * 画布引用快照不受影响（引用方 file_id 语义）。
     */
    @Transactional
    public AssetVO archive(Long assetId, Long userId, boolean admin) {
        Asset asset = loadAsset(assetId);
        aclService.requireWrite(asset.getProjectId(), userId, admin);
        if (Asset.STATUS_ARCHIVED.equals(asset.getStatus())) {
            return toVO(asset, false);
        }
        asset.setStatus(Asset.STATUS_ARCHIVED);
        assetMapper.updateById(asset);
        log.info("asset archived: id={} userId={}", assetId, userId);
        return toVO(asset, false);
    }

    /**
     * 取消归档恢复：ARCHIVED→DRAFT（L3「取消归档恢复」）。非 ARCHIVED 400。
     */
    @Transactional
    public AssetVO unarchive(Long assetId, Long userId, boolean admin) {
        Asset asset = loadAsset(assetId);
        aclService.requireWrite(asset.getProjectId(), userId, admin);
        if (!Asset.STATUS_ARCHIVED.equals(asset.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅归档资产可恢复");
        }
        asset.setStatus(Asset.STATUS_DRAFT);
        assetMapper.updateById(asset);
        log.info("asset unarchived: id={} userId={}", assetId, userId);
        return toVO(asset, false);
    }

    /** 保存一致性包（委托版本服务产新版本）。requireWrite。 */
    @Transactional
    public AssetVO saveConsistencyPack(Long assetId, Long userId, boolean admin,
                                       com.superprogrammer.asset.dto.ConsistencyPackRequest req) {
        Asset asset = loadAsset(assetId);
        aclService.requireWrite(asset.getProjectId(), userId, admin);
        versionService.saveConsistencyPack(assetId, userId, admin, req);
        // 回读最新态（current_version/content 已被版本服务更新）
        return toVO(loadAsset(assetId), true);
    }

    // ---------- 角色挂载同步 ----------

    /**
     * 同步资产角色挂载（受控词汇校验：roleKey 须在项目 narrative_roles 内）。
     *
     * <p>公开给画布打通（AssetCanvasBridgeService）复用同一受控词汇校验，
     * 避免在 Bridge 重复 vocab 逻辑（单一事实源）。
     */
    public void attachRoles(Long projectId, Long assetId, List<String> roleKeys) {
        syncRoleLinks(projectId, assetId, roleKeys);
    }

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

    /** content 须为合法 JSON（content 列为 JSONB；防非 JSON 直传 → DB 500，前置 400）。 */
    void validateContentJson(String content) {
        try {
            objectMapper.readTree(content);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文不是合法的 JSON（应为 {\"body\":\"...\"} / {\"synopsis\":\"...\"}）");
        }
    }

    private boolean isTextType(String mediaType) {
        return Asset.MEDIA_PROMPT.equals(mediaType) || Asset.MEDIA_SCRIPT.equals(mediaType);
    }

    private boolean isFileType(String mediaType) {
        return Asset.MEDIA_IMAGE.equals(mediaType) || Asset.MEDIA_VIDEO.equals(mediaType) || Asset.MEDIA_AUDIO.equals(mediaType);
    }

    /** 类型↔资产类型匹配校验（mp4 不可入图片资产，安全清单）。 */
    private void validateFileMime(String mediaType, String mime) {
        String m = mime == null ? "" : mime.toLowerCase();
        boolean ok = switch (mediaType) {
            case Asset.MEDIA_IMAGE -> m.startsWith("image/");
            case Asset.MEDIA_VIDEO -> m.startsWith("video/");
            case Asset.MEDIA_AUDIO -> m.startsWith("audio/");
            default -> false;
        };
        if (!ok) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "文件类型与资产类型不匹配：" + mediaType + " 需对应 MIME 前缀");
        }
    }

    /**
     * 构造上传生成谱系 JSON（含技术元数据）。
     * 图片：JDK ImageIO 同步读宽高；视频/音频：时长/分辨率懒提取（javacv，MVP 仅记基础信息，TODO 后续补）。
     */
    private String buildUploadGenMeta(String mediaType, StoredFile stored, MultipartFile file) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode upload = root.putObject("upload");
            upload.put("originalName", stored.name());
            upload.put("mime", stored.mimeType());
            upload.put("size", stored.size());
            if (Asset.MEDIA_IMAGE.equals(mediaType)) {
                int[] dims = readImageDims(file);
                if (dims != null) {
                    ObjectNode img = root.putObject("image");
                    img.put("width", dims[0]);
                    img.put("height", dims[1]);
                }
            }
            // 视频/音频技术元数据（时长/分辨率/码率）走 javacv 懒提取，MVP 暂不入库
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("build genMeta failed: {}", e.getMessage());
            return "{}";
        }
    }

    /** 读图片宽高（JDK ImageIO，无原生依赖）。失败返 null（容错不阻断上传）。 */
    private int[] readImageDims(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            BufferedImage img = ImageIO.read(in);
            if (img != null) {
                return new int[]{img.getWidth(), img.getHeight()};
            }
        } catch (Exception e) {
            log.warn("read image dims failed: {}", e.getMessage());
        }
        return null;
    }

    /** 资产名校验（≤100），公开给画布打通复用同一口径。 */
    public String validateAssetName(String name) {
        return validateName(name);
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
