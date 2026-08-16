package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superprogrammer.asset.dto.AssetCreateRequest;
import com.superprogrammer.asset.dto.AssetCopyRequest;
import com.superprogrammer.asset.dto.AssetUpdateRequest;
import com.superprogrammer.asset.dto.AssetVO;
import com.superprogrammer.asset.dto.MatrixCountVO;
import com.superprogrammer.asset.dto.MediaTypeDef;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetProject;
import com.superprogrammer.asset.entity.AssetRoleLink;
import com.superprogrammer.asset.entity.AssetVersion;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetProjectMapper;
import com.superprogrammer.asset.mapper.AssetRoleLinkMapper;
import com.superprogrammer.asset.mapper.AssetVersionMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.security.util.ImageGuard;
import com.superprogrammer.system.service.SystemSettingService;
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
    /** 文本类正文片段截断上限（S16 卡片封面预览）。 */
    private static final int TEXT_PREVIEW_MAX = 120;
    /** 分镜提示词上限（S18 字段1，同剧本 synopsis 上限）。 */
    private static final int STORYBOARD_PROMPT_MAX = 8000;
    /** 分镜实体键上限（S18 字段2/4 key）。 */
    private static final int STORYBOARD_REF_KEY_MAX = 32;
    /** 图片目录注入 LLM 默认上限（防 prompt token 爆，运维清单可调）。 */
    private static final int ASSET_CATALOG_DEFAULT_LIMIT = 50;

    private final AssetMapper assetMapper;
    private final AssetVersionMapper versionMapper;
    private final AssetRoleLinkMapper roleLinkMapper;
    private final AssetProjectMapper projectMapper;
    private final AssetAclService aclService;
    private final ObjectMapper objectMapper;
    private final FileStorageService fileStorageService;
    private final AssetVersionService versionService;
    private final com.superprogrammer.asset.mapper.AssetScoreMapper scoreMapper;
    private final com.superprogrammer.auth.mapper.UserMapper userMapper;

    /** 安全体系 S4 · SEC-FR-032 像素上限读取（F-3①）。横切可选依赖：@RequiredArgsConstructor
     * 构造注入不填本字段 → null 时用 ImageGuard 默认上限，不破坏既有测试切片。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SystemSettingService systemSettingService;

    /**
     * 该 fileId 是否为当前用户可访问的资产文件（项目成员 viewer+ 可读即放行；admin 放行）。
     *
     * <p>供 media 生成附件归属校验：资产库文件 {@code owner_user_id} 为上传者，项目其他成员
     * 选他人上传的资产作多模态参考时，按项目成员身份放行（与 canvas bridge resolve 一致），
     * 而非要求文件归属相等。非资产文件（查不到 asset_version）返 false，调用方回落原 owner 校验。
     *
     * @param fileId 附件 file_id（stored_files.file_id）
     * @param userId 当前用户（nullable）
     * @param admin  是否 admin
     * @return true=可作附件使用；false=非资产文件或无项目访问权
     */
    public boolean isAttachmentFileAccessible(String fileId, Long userId, boolean admin) {
        if (fileId == null || fileId.isBlank()) {
            return false;
        }
        AssetVersion v = versionMapper.selectOne(new LambdaQueryWrapper<AssetVersion>()
                .eq(AssetVersion::getFileId, fileId).last("LIMIT 1"));
        if (v == null) {
            return false;
        }
        Asset asset = assetMapper.selectById(v.getAssetId());
        if (asset == null) {
            return false;
        }
        try {
            aclService.loadAccessible(asset.getProjectId(), userId, admin);
            return true;
        } catch (BusinessException e) {
            // 非项目成员（FORBIDDEN）→ 不可用作附件
            return false;
        }
    }

    /** 新建文本类资产（TEXT 类别：PROMPT/SCRIPT 或自定义 TEXT 类型）+ 版本 1。文件类经上传端点。 */
    @Transactional
    public AssetVO create(Long projectId, Long userId, boolean admin, AssetCreateRequest req) {
        aclService.requireWrite(projectId, userId, admin);
        Asset asset = internalCreateText(projectId, req.getMediaType(), req.getName(),
                req.getDescription(), req.getContent(), req.getRoleKeys(), userId);
        log.info("asset created: id={} projectId={} mediaType={} userId={}",
                asset.getId(), projectId, req.getMediaType(), userId);
        return toVO(asset, false);
    }

    /**
     * 将源资产当前版本复制成目标项目内的独立资产。
     * 只复用版本中的 fileId 引用，不复制物理文件、历史版本或画布绑定。
     */
    @Transactional(rollbackFor = Exception.class)
    public AssetVO copyCurrent(Long sourceAssetId, Long userId, boolean admin, AssetCopyRequest request) {
        Asset source = loadAsset(sourceAssetId);
        aclService.loadAccessible(source.getProjectId(), userId, admin);
        Long targetProjectId = request == null ? null : request.getTargetProjectId();
        if (targetProjectId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "目标项目不能为空");
        }
        aclService.requireWrite(targetProjectId, userId, admin);

        if (assetMapper.lockByIdForUpdate(sourceAssetId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "源资产不存在");
        }

        AssetVersion current = versionMapper.selectOne(new LambdaQueryWrapper<AssetVersion>()
                .eq(AssetVersion::getAssetId, sourceAssetId)
                .eq(AssetVersion::getVersion, source.getCurrentVersion()));
        if (current == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "源资产当前版本不存在");
        }
        AssetProject target = projectMapper.selectById(targetProjectId);
        if (target == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目标项目不存在");
        }
        ensureTargetMediaType(target, source.getMediaType(), source.getMediaCategory());

        Set<String> targetRoles = new LinkedHashSet<>(loadNarrativeRoles(targetProjectId));
        List<String> copiedRoles = roleLinkMapper.selectList(new LambdaQueryWrapper<AssetRoleLink>()
                        .eq(AssetRoleLink::getAssetId, sourceAssetId))
                .stream()
                .map(AssetRoleLink::getRoleKey)
                .filter(targetRoles::contains)
                .distinct()
                .toList();

        Asset copy = new Asset();
        copy.setProjectId(targetProjectId);
        copy.setMediaType(source.getMediaType());
        copy.setMediaCategory(source.getMediaCategory());
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setTags(source.getTags());
        copy.setStatus(Asset.STATUS_DRAFT);
        copy.setContent(current.getContent());
        copy.setGenMeta(source.getGenMeta());
        copy.setCurrentVersion(1);
        // 2x第三轮C6：复制者成为新资产创建者（PERSONAL 所有权判据；与源资产 createdBy 解耦）
        copy.setCreatedBy(userId);
        assetMapper.insert(copy);

        AssetVersion firstVersion = new AssetVersion();
        firstVersion.setAssetId(copy.getId());
        firstVersion.setVersion(1);
        firstVersion.setFileId(current.getFileId());
        firstVersion.setContent(current.getContent());
        firstVersion.setChangeNote("复制自公共资产 " + sourceAssetId + " 当前版本 v" + source.getCurrentVersion());
        versionMapper.insert(firstVersion);

        for (String roleKey : copiedRoles) {
            AssetRoleLink link = new AssetRoleLink();
            link.setAssetId(copy.getId());
            link.setRoleKey(roleKey);
            roleLinkMapper.insert(link);
        }
        log.info("asset copied: action=copy sourceProjectId={} targetProjectId={} sourceAssetId={} targetAssetId={} userId={}",
                source.getProjectId(), targetProjectId, sourceAssetId, copy.getId(), userId);

        AssetVO result = toVO(copy, true);
        result.setFileId(current.getFileId());
        result.setRoleKeys(copiedRoles);
        return result;
    }

    /**
     * 文本类资产建库核心（剥离 ACL 校验，供 {@code create} 与一键分镜循环复用，DRY）。
     *
     * <p>调用方自行 ensure {@code requireWrite}（分镜在循环外校验一次）。流程：resolveCategory→须 TEXT→
     * 校验名/正文 JSON→insert 资产+版本 1+角色挂载。返 {@link Asset}（含自增 id）供调用方收集。
     *
     * <p>2x第三轮C6：显式落 {@code createdBy}（PERSONAL 模式所有权判据；V124 前存量 NULL 根因即各创建路径不写值）。
     */
    @Transactional
    public Asset internalCreateText(Long projectId, String mediaType, String name,
                                    String description, String contentJson, List<String> roleKeys,
                                    Long creatorUserId) {
        String category = resolveCategory(projectId, mediaType);
        if (!isTextCategory(category)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "新建仅支持文本类（TEXT 类别）资产，文件类请用上传");
        }
        if (contentJson == null || contentJson.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文本类资产正文不能为空");
        }
        validateContentJson(contentJson);
        Asset asset = new Asset();
        asset.setProjectId(projectId);
        asset.setMediaType(mediaType);
        asset.setMediaCategory(category);
        asset.setName(validateName(name));
        asset.setDescription(description);
        asset.setStatus(Asset.STATUS_DRAFT);
        asset.setTags(serializeList(roleKeys == null ? List.of() : roleKeys));
        asset.setCurrentVersion(1);
        asset.setContent(contentJson);
        asset.setGenMeta("{}");
        asset.setCreatedBy(creatorUserId);
        assetMapper.insert(asset);
        AssetVersion v1 = new AssetVersion();
        v1.setAssetId(asset.getId());
        v1.setVersion(1);
        v1.setContent(asset.getContent());
        versionMapper.insert(v1);
        syncRoleLinks(projectId, asset.getId(), roleKeys);
        return asset;
    }

    /**
     * 加载项目图片资产目录（供一键分镜 LLM 注入，S19）：category=IMAGE 且 roleKeys ∩ {人物,道具,场景}，
     * ≤{@code limit}（默认 50，防 prompt token 爆；运维清单），updated_at 倒序。紧凑字段不带 content（安全/省 token）。
     */
    public List<ImageCatalogItem> getImageCatalog(Long projectId, int limit) {
        int cap = (limit <= 0 || limit > 200) ? ASSET_CATALOG_DEFAULT_LIMIT : limit;
        List<Asset> images = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getProjectId, projectId)
                .eq(Asset::getMediaCategory, Asset.CATEGORY_IMAGE)
                .orderByDesc(Asset::getUpdatedAt));
        if (images.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> roleMap = rolesOf(images.stream().map(Asset::getId).collect(Collectors.toList()));
        List<String> entityRoles = List.of(AssetProjectService.DEFAULT_NARRATIVE_ROLES.get(0),
                AssetProjectService.DEFAULT_NARRATIVE_ROLES.get(1),
                AssetProjectService.DEFAULT_NARRATIVE_ROLES.get(2));
        List<ImageCatalogItem> out = new ArrayList<>();
        for (Asset a : images) {
            List<String> rks = roleMap.getOrDefault(a.getId(), Collections.emptyList());
            if (rks.stream().noneMatch(entityRoles::contains)) {
                continue;
            }
            out.add(new ImageCatalogItem(a.getId(), a.getName(), a.getMediaType(), rks));
            if (out.size() >= cap) {
                break;
            }
        }
        return out;
    }

    /** 图片目录条目（LLM 注入用，紧凑无 content）。 */
    public record ImageCatalogItem(Long id, String name, String mediaType, List<String> roleKeys) {
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
        String category = resolveCategory(projectId, mediaType);
        if (!isFileCategory(category)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传仅支持图片/视频/音频类（IMAGE/VIDEO/AUDIO 类别）资产");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }
        validateFileMime(category, file.getContentType());
        // 安全体系 S4 · SEC-FR-032：图片类像素预算（F-3① 像素炸弹）——落盘前头读取校验，超限拒收
        if ("IMAGE".equals(category)) {
            guardImagePixels(file);
        }
        // 落盘 + 登记 owner（SOURCE_ASSET）
        StoredFile stored = fileStorageService.store(file, userId, StoredFileEntity.SOURCE_ASSET);
        // 名称：缺省用原始文件名
        String safeName = validateName((name == null || name.isBlank()) ? stored.name() : name);
        Asset asset = new Asset();
        asset.setProjectId(projectId);
        asset.setMediaType(mediaType);
        asset.setMediaCategory(category);
        asset.setName(safeName);
        asset.setDescription(description);
        asset.setStatus(Asset.STATUS_DRAFT);
        asset.setTags("[]");
        asset.setContent("{}");
        asset.setCurrentVersion(1);
        asset.setGenMeta(buildUploadGenMeta(category, stored, file));
        // 2x第三轮C6：上传者即创建者（PERSONAL 所有权判据）
        asset.setCreatedBy(userId);
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
     *
     * <p>2x第三轮C6 新增筛选：creatorUsername（users 精确匹配 → IN createdBy；0 命中空页）；
     * scoreMin/scoreMax/scoreSource（owner=拥有者分行过滤 / member=成员均分 GROUP BY HAVING，
     * 与既有 type×role×q×status 同交集 wrapper 叠加）。
     * 装配 createdByUsername + 双轨评分 + myScore（批查防 N+1）。
     */
    public PageResult<AssetVO> list(Long projectId, Long userId, boolean admin,
                                    String mediaType, String role, String q, String status,
                                    String creatorUsername, Integer scoreMin, Integer scoreMax, String scoreSource,
                                    int page, int size) {
        aclService.loadAccessible(projectId, userId, admin);
        LambdaQueryWrapper<Asset> w = new LambdaQueryWrapper<>();
        w.eq(Asset::getProjectId, projectId);
        if (mediaType != null && !mediaType.isBlank()) {
            resolveCategory(projectId, mediaType);
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
        // 上传者过滤（C6）：username 精确查 users → IN createdBy；0 命中直接空页
        if (creatorUsername != null && !creatorUsername.isBlank()) {
            String name = creatorUsername.trim();
            if (name.length() > Q_MAX) {
                name = name.substring(0, Q_MAX);
            }
            List<Long> creatorIds = userMapper.selectList(new LambdaQueryWrapper<com.superprogrammer.auth.entity.User>()
                            .eq(com.superprogrammer.auth.entity.User::getUsername, name))
                    .stream().map(com.superprogrammer.auth.entity.User::getId).collect(Collectors.toList());
            if (creatorIds.isEmpty()) {
                return PageResult.of(Collections.emptyList(), 0L, page, size);
            }
            w.in(Asset::getCreatedBy, creatorIds);
        }
        // 评分过滤（C6）：先子查询圈 asset_ids（owner 分轨 / member 均分 HAVING），再 in() 与其余条件交集
        if (scoreSource != null && !scoreSource.isBlank()
                || scoreMin != null || scoreMax != null) {
            String source = scoreSource == null ? "" : scoreSource.trim();
            if (!"owner".equals(source) && !"member".equals(source)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "评分来源须为 owner 或 member");
            }
            if (scoreMin != null && (scoreMin < 0 || scoreMin > 100)
                    || scoreMax != null && (scoreMax < 0 || scoreMax > 100)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "分数区间须在 0-100 内");
            }
            if (scoreMin != null && scoreMax != null && scoreMin > scoreMax) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "分数区间下限不能大于上限");
            }
            List<Long> ids = "owner".equals(source)
                    ? scoreMapper.selectAssetIdsByOwnerScore(projectId, scoreMin, scoreMax)
                    : scoreMapper.selectAssetIdsByMemberAvg(projectId, scoreMin, scoreMax);
            if (ids.isEmpty()) {
                return PageResult.of(Collections.emptyList(), 0L, page, size);
            }
            w.in(Asset::getId, ids);
        }
        w.orderByDesc(Asset::getUpdatedAt);
        IPage<Asset> p = assetMapper.selectPage(new Page<>(page, size <= 0 ? DEFAULT_PAGE_SIZE : size), w);
        List<AssetVO> vos = assembleList(projectId, userId, p.getRecords());
        return PageResult.of(vos, p.getTotal(), (int) p.getCurrent(), (int) p.getSize());
    }

    /**
     * 列表态批量装配（C6）：assembleRoles（角色/fileId）之上叠加
     * createdByUsername（users 批查）+ 双轨评分 + myScore（asset_scores 按项目两次批查）。
     */
    private List<AssetVO> assembleList(Long projectId, Long userId, List<Asset> assets) {
        List<AssetVO> vos = assembleRoles(assets, false);
        if (vos.isEmpty()) {
            return vos;
        }
        // 上传者用户名：批查一次（含 null createdBy 的存量近似行）
        Set<Long> creatorIds = assets.stream()
                .map(Asset::getCreatedBy).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> usernameMap = creatorIds.isEmpty() ? Collections.emptyMap()
                : userMapper.selectBatchIds(creatorIds).stream()
                        .collect(Collectors.toMap(com.superprogrammer.auth.entity.User::getId,
                                u -> u.getUsername() == null ? "" : u.getUsername(), (a, b) -> a));
        // 双轨聚合 + 我的分：按项目各一次（FILTER GROUP BY / scorer 维度），内存按 assetId 对齐
        Map<Long, Object[]> aggMap = new java.util.HashMap<>();
        for (Map<String, Object> row : scoreMapper.selectAggregatesByProject(projectId)) {
            Object[] agg = new Object[3];
            agg[0] = row.get("ownerScore");
            agg[1] = row.get("memberAvgScore");
            agg[2] = row.get("memberCount");
            aggMap.put(((Number) row.get("assetId")).longValue(), agg);
        }
        Map<Long, Integer> myMap = new java.util.HashMap<>();
        for (Map<String, Object> row : scoreMapper.selectMyScores(projectId, userId)) {
            myMap.put(((Number) row.get("assetId")).longValue(), ((Number) row.get("score")).intValue());
        }
        for (int i = 0; i < assets.size(); i++) {
            AssetVO vo = vos.get(i);
            vo.setCreatedByUsername(usernameMap.get(assets.get(i).getCreatedBy()));
            Object[] agg = aggMap.get(assets.get(i).getId());
            if (agg != null) {
                vo.setOwnerScore(agg[0] == null ? null : ((Number) agg[0]).intValue());
                // PG AVG 返回 NUMERIC（精度任意），统一 Number.intValue 取整展示
                vo.setMemberAvgScore(agg[1] == null ? null : (int) Math.round(((Number) agg[1]).doubleValue()));
                vo.setMemberCount(agg[2] == null ? 0 : ((Number) agg[2]).intValue());
            } else {
                vo.setMemberCount(0);
            }
            vo.setMyScore(myMap.get(assets.get(i).getId()));
        }
        return vos;
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

    /** 更新 meta + 分类（正文改版走版本端点 S5）。requireAssetOperate（C6）。 */
    @Transactional
    public AssetVO update(Long assetId, Long userId, boolean admin, AssetUpdateRequest req) {
        Asset asset = loadAsset(assetId);
        aclService.requireAssetOperate(asset, userId, admin);
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

    /** 软删（requireAssetOperate，C6）。role_links 硬删；bindings 留存历史（引用快照语义）。 */
    @Transactional
    public void delete(Long assetId, Long userId, boolean admin) {
        Asset asset = loadAsset(assetId);
        aclService.requireAssetOperate(asset, userId, admin);
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
        aclService.requireAssetOperate(asset, userId, admin);
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
        aclService.requireAssetOperate(asset, userId, admin);
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
        aclService.requireAssetOperate(asset, userId, admin);
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
        aclService.requireAssetOperate(asset, userId, admin);
        if (!Asset.STATUS_ARCHIVED.equals(asset.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅归档资产可恢复");
        }
        asset.setStatus(Asset.STATUS_DRAFT);
        assetMapper.updateById(asset);
        log.info("asset unarchived: id={} userId={}", assetId, userId);
        return toVO(asset, false);
    }

    /** 保存一致性包（委托版本服务产新版本）。requireAssetOperate（C6）。 */
    @Transactional
    public AssetVO saveConsistencyPack(Long assetId, Long userId, boolean admin,
                                       com.superprogrammer.asset.dto.ConsistencyPackRequest req) {
        Asset asset = loadAsset(assetId);
        aclService.requireAssetOperate(asset, userId, admin);
        versionService.saveConsistencyPack(assetId, userId, admin, req);
        // 回读最新态（current_version/content 已被版本服务更新）
        return toVO(loadAsset(assetId), true);
    }

    /**
     * 保存分镜字段（S18，5 字段流水线字段 1/2/4）。requireWrite + 须分镜类型。
     *
     * <p>合并进 content（保留 shotIndex/parentId/imageGen/videoGen），产新版本。
     * entityRefs/videoInputs 的 assetId 逐个校验 ∈ 同项目（query 缩圈 projectId），
     * 非法/跨项目/已删 → 置 null（剔除防越权，保留 key 存痕迹）；富化 name/mediaType 取自目录非客户端。
     */
    @Transactional
    public AssetVO saveStoryboard(Long assetId, Long userId, boolean admin,
                                  com.superprogrammer.asset.dto.StoryboardSaveRequest req) {
        Asset asset = loadAsset(assetId);
        aclService.requireAssetOperate(asset, userId, admin);
        if (!Asset.MEDIA_STORYBOARD.equals(asset.getMediaType())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅分镜类型资产可保存分镜字段");
        }
        ObjectNode content = parseContentObject(asset.getContent());
        // 字段1 prompt（≤8000）
        if (req.getPrompt() != null) {
            if (req.getPrompt().length() > STORYBOARD_PROMPT_MAX) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "分镜提示词不得超过 " + STORYBOARD_PROMPT_MAX + " 字");
            }
            content.put("prompt", req.getPrompt());
        }
        // 字段2 entityRefs：校验同项目 + 富化
        if (req.getEntityRefs() != null) {
            content.set("entityRefs", enrichRefs(req.getEntityRefs(), asset.getProjectId()));
        }
        // 字段4 videoInputs：audioRefs/imageRefs 两组
        if (req.getVideoInputs() != null) {
            ObjectNode vi = objectMapper.createObjectNode();
            vi.set("audioRefs", enrichRefs(req.getVideoInputs().getAudioRefs(), asset.getProjectId()));
            vi.set("imageRefs", enrichRefs(req.getVideoInputs().getImageRefs(), asset.getProjectId()));
            content.set("videoInputs", vi);
        }
        com.superprogrammer.asset.dto.VersionCreateRequest vr = new com.superprogrammer.asset.dto.VersionCreateRequest();
        vr.setContent(content.toString());
        vr.setChangeNote("编辑分镜字段");
        versionService.createVersion(assetId, userId, admin, vr);
        log.info("storyboard saved: assetId={} projectId={} userId={}", assetId, asset.getProjectId(), userId);
        return get(assetId, userId, admin);
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

    /**
     * 列表批量组装角色 + 当前版本 fileId（单次 IN 查询，内存分组，防 N+1）。
     *
     * <p>fileId 供前端卡片缩略图懒加载（C2）：仅文件类资产（IMAGE/VIDEO/AUDIO）版本有值；
     * 列表态省 content，fileId 单独返省流量。文本类版本 fileId=null，VO.fileId 留空。
     */
    private List<AssetVO> assembleRoles(List<Asset> assets, boolean withContent) {
        if (assets.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = assets.stream().map(Asset::getId).collect(Collectors.toList());
        Map<Long, List<String>> roleMap = rolesOf(ids);
        Map<Long, String> fileIdMap = currentFileIdsOf(assets, ids);
        return assets.stream().map(a -> {
            AssetVO vo = toVO(a, withContent);
            vo.setRoleKeys(roleMap.getOrDefault(a.getId(), Collections.emptyList()));
            vo.setFileId(fileIdMap.get(a.getId()));
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 批量取当前版本 fileId：单次 IN 查 asset_versions，内存按 (assetId,currentVersion) 过滤
     * （防 N+1；列表分页 size≤20，版本历史小，全量取回后过滤可接受）。
     */
    private Map<Long, String> currentFileIdsOf(List<Asset> assets, List<Long> ids) {
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Integer> curVer = assets.stream()
                .collect(Collectors.toMap(Asset::getId, Asset::getCurrentVersion, (a, b) -> a));
        List<AssetVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<AssetVersion>()
                .in(AssetVersion::getAssetId, ids));
        if (versions == null) {
            return Collections.emptyMap();
        }
        Map<Long, String> m = new java.util.HashMap<>();
        for (AssetVersion v : versions) {
            if (curVer.get(v.getAssetId()) != null && curVer.get(v.getAssetId()).equals(v.getVersion())) {
                m.put(v.getAssetId(), v.getFileId());
            }
        }
        return m;
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

    /**
     * 解析媒体类型→处理类别，并校验该 type 在项目受控词汇内（V60 §C1b）。
     * 命中 vocab 返其 category；不在受控词汇 → 400（仿 syncRoleLinks 受控词汇校验，单一事实源）。
     */
    private String resolveCategory(Long projectId, String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "媒体类型不能为空");
        }
        Map<String, MediaTypeDef> vocab = loadMediaTypeVocab(projectId);
        MediaTypeDef def = vocab.get(mediaType.trim());
        if (def == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "媒体类型「" + mediaType + "」不在项目受控词汇内");
        }
        return def.getCategory();
    }

    /** content 须为合法 JSON（content 列为 JSONB；防非 JSON 直传 → DB 500，前置 400）。 */
    void validateContentJson(String content) {
        try {
            objectMapper.readTree(content);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文不是合法的 JSON（应为 {\"body\":\"...\"} / {\"synopsis\":\"...\"}）");
        }
    }

    private boolean isTextCategory(String category) {
        return Asset.CATEGORY_TEXT.equals(category);
    }

    private boolean isFileCategory(String category) {
        return Asset.CATEGORY_IMAGE.equals(category)
                || Asset.CATEGORY_VIDEO.equals(category)
                || Asset.CATEGORY_AUDIO.equals(category);
    }

    /** category↔MIME 匹配校验（mp4 不可入 IMAGE 类别，安全清单；V60 按 category 而非 type）。 */
    private void validateFileMime(String category, String mime) {
        String m = mime == null ? "" : mime.toLowerCase();
        boolean ok = switch (category) {
            case Asset.CATEGORY_IMAGE -> m.startsWith("image/");
            case Asset.CATEGORY_VIDEO -> m.startsWith("video/");
            case Asset.CATEGORY_AUDIO -> m.startsWith("audio/");
            default -> false;
        };
        if (!ok) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "文件类型与处理类别不匹配：" + category + " 需对应 MIME 前缀");
        }
    }

    /**
     * 构造上传生成谱系 JSON（含技术元数据）。
     * IMAGE 类别：JDK ImageIO 同步读宽高；VIDEO/AUDIO：时长/分辨率懒提取（javacv，MVP 仅记基础信息，TODO 后续补）。
     */
    private String buildUploadGenMeta(String category, StoredFile stored, MultipartFile file) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode upload = root.putObject("upload");
            upload.put("originalName", stored.name());
            upload.put("mime", stored.mimeType());
            upload.put("size", stored.size());
            if (Asset.CATEGORY_IMAGE.equals(category)) {
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

    /**
     * 图片像素预算校验（S4 F-3①）：只读文件头不解码像素（原 ImageIO.read 全量解码是 OOM 面）。
     * 无法判定放行（检测层不自残），超限 BAD_REQUEST 拒收。
     */
    private void guardImagePixels(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            long cap = systemSettingService == null
                    ? ImageGuard.DEFAULT_MAX_PIXELS
                    : systemSettingService.getUploadMaxPixels();
            ImageGuard.assertPixels(cap, in, file.getOriginalFilename());
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.warn("image pixel guard failed(放行) name={}: {}", file.getOriginalFilename(), e.getMessage());
        }
    }

    /** 读图片宽高（S4 F-3 起改头读取，不解码像素——原 ImageIO.read 是像素炸弹面）。失败返 null（容错）。 */
    private int[] readImageDims(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return ImageGuard.dimensions(in);
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

    /** 加载项目媒体类型受控词汇 → key→def 映射（V60 §C1b，受控校验单一事实源）。 */
    private Map<String, MediaTypeDef> loadMediaTypeVocab(Long projectId) {
        AssetProject p = projectMapper.selectById(projectId);
        List<MediaTypeDef> types;
        if (p == null || p.getMediaTypes() == null || p.getMediaTypes().isBlank()) {
            types = new ArrayList<>(AssetProjectService.DEFAULT_MEDIA_TYPES);
        } else {
            try {
                types = objectMapper.readValue(p.getMediaTypes(), new TypeReference<List<MediaTypeDef>>() {});
                if (types == null || types.isEmpty()) {
                    types = new ArrayList<>(AssetProjectService.DEFAULT_MEDIA_TYPES);
                }
            } catch (Exception e) {
                log.warn("parse mediaTypes failed projectId={}: {}", projectId, e.getMessage());
                types = new ArrayList<>(AssetProjectService.DEFAULT_MEDIA_TYPES);
            }
        }
        Map<String, MediaTypeDef> m = new java.util.LinkedHashMap<>();
        for (MediaTypeDef t : types) {
            if (t != null && t.getKey() != null) {
                m.put(t.getKey(), t);
            }
        }
        return m;
    }

    /** 复制时按源类型补齐目标项目媒体词汇；同名类型若类别冲突则拒绝，避免错误编辑器接管。 */
    private void ensureTargetMediaType(AssetProject target, String mediaType, String mediaCategory) {
        Map<String, MediaTypeDef> vocab = loadMediaTypeVocab(target.getId());
        MediaTypeDef existing = vocab.get(mediaType);
        if (existing != null) {
            if (!java.util.Objects.equals(existing.getCategory(), mediaCategory)) {
                throw new BusinessException(ErrorCode.CONFLICT, "目标项目存在同名但类别不同的媒体类型");
            }
            return;
        }
        vocab.put(mediaType, new MediaTypeDef(mediaType, mediaCategory));
        try {
            target.setMediaTypes(objectMapper.writeValueAsString(new ArrayList<>(vocab.values())));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "目标项目媒体类型更新失败");
        }
        projectMapper.updateById(target);
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
        // S16：TEXT 类别资产填正文片段（列表态卡片封面用，独立于 content 懒加载）
        String textPreview = isTextCategory(a.getMediaCategory()) ? extractTextPreview(a.getContent()) : null;
        return AssetVO.builder()
                .id(a.getId())
                .projectId(a.getProjectId())
                .mediaType(a.getMediaType())
                .mediaCategory(a.getMediaCategory())
                .name(a.getName())
                .description(a.getDescription())
                .tags(tags)
                .status(a.getStatus())
                .content(withContent ? a.getContent() : null)
                .textPreview(textPreview)
                .genMeta(a.getGenMeta())
                .currentVersion(a.getCurrentVersion())
                .createdBy(a.getCreatedBy())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }

    /**
     * 抽取文本类资产正文片段（S16 Bug④，列表态卡片封面）。
     *
     * <p>按键优先级 {@code body}/{@code synopsis}/{@code prompt} 取值（prompt 供分镜卡）；
     * 命中不到兜底首个字符串字段；非合法 JSON 当裸文本。剥为纯文本（空白合一去换行）+ 截断 ≤120 字。
     * 仅 TEXT 类别资产由 {@link #toVO} 门控调用；非 TEXT 返 null。
     *
     * <p>与 {@code AssetScriptService.readScriptBody} 各司其职：后者为 LLM 读正文（含分场 fallback，
     * 语义不同），本方法仅做卡片预览展示。
     */
    private String extractTextPreview(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String raw = null;
        try {
            JsonNode root = objectMapper.readTree(content);
            for (String key : new String[]{"body", "synopsis", "prompt"}) {
                JsonNode n = root.get(key);
                if (n != null && n.isTextual()) {
                    raw = n.asText();
                    break;
                }
            }
            if (raw == null) {
                // 兜底：首个字符串字段（自定义 content schema）
                for (JsonNode n : root) {
                    if (n.isTextual()) {
                        raw = n.asText();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            raw = content; // 非合法 JSON，当裸文本截断
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > TEXT_PREVIEW_MAX ? cleaned.substring(0, TEXT_PREVIEW_MAX) : cleaned;
    }

    /** 解析 content 为 ObjectNode（非合法 JSON/空 → 空 ObjectNode）。S18 分镜合并用。 */
    private ObjectNode parseContentObject(String content) {
        try {
            if (content == null || content.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return (ObjectNode) objectMapper.readTree(content);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /**
     * 富化分镜实体引用列表（S18 字段2/4）：批量取项目内资产，逐条校验 + 富化。
     *
     * <p>assetId 经 fetchAssetsInProject 缩圈 projectId 查得 → 命中富化 name/mediaType；
     * 非法/跨项目/已删 → 置 null（保留 key 存痕迹，剔除 assetId 防越权引他项目资产）。
     * key 校验 ≤32；空 key 跳过。
     */
    private com.fasterxml.jackson.databind.node.ArrayNode enrichRefs(
            List<com.superprogrammer.asset.dto.StoryboardSaveRequest.EntityRef> refs, Long projectId) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = objectMapper.createArrayNode();
        if (refs == null) {
            return arr;
        }
        Set<Long> ids = refs.stream()
                .map(com.superprogrammer.asset.dto.StoryboardSaveRequest.EntityRef::getAssetId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Asset> map = ids.isEmpty() ? Collections.emptyMap() : fetchAssetsInProject(ids, projectId);
        for (com.superprogrammer.asset.dto.StoryboardSaveRequest.EntityRef r : refs) {
            if (r.getKey() == null || r.getKey().isBlank()) {
                continue;
            }
            String key = r.getKey().trim();
            if (key.length() > STORYBOARD_REF_KEY_MAX) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "分镜实体键不得超过 " + STORYBOARD_REF_KEY_MAX + " 字");
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("key", key);
            Asset ref = r.getAssetId() == null ? null : map.get(r.getAssetId());
            if (ref != null) {
                node.put("assetId", ref.getId());
                node.put("name", ref.getName());
                node.put("mediaType", ref.getMediaType());
            } else if (r.getAssetId() != null) {
                node.putNull("assetId");
            }
            arr.add(node);
        }
        return arr;
    }

    /** 批量取项目内资产（query 缩圈 projectId，防跨项目越权引用）。S18 分镜富化用。 */
    private Map<Long, Asset> fetchAssetsInProject(Set<Long> ids, Long projectId) {
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .in(Asset::getId, ids)
                .eq(Asset::getProjectId, projectId));
        return assets.stream().collect(Collectors.toMap(Asset::getId, a -> a, (a, b) -> a));
    }
}
