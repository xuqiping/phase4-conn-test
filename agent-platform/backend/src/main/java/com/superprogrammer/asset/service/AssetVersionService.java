package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superprogrammer.asset.dto.VersionCreateRequest;
import com.superprogrammer.asset.dto.VersionVO;
import com.superprogrammer.asset.entity.Asset;
import com.superprogrammer.asset.entity.AssetVersion;
import com.superprogrammer.asset.mapper.AssetMapper;
import com.superprogrammer.asset.mapper.AssetVersionMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 项目资产库·版本与一致性包（plan §S5 / FR-006/007，设计方案 §五/§六）。
 *
 * <p>版本：自动版本（无人工干预，DAM 实践），乐观锁防并发撞号（plan 坑点预判）。
 * 列表 meta only（不带回大文本）；单取带 content。
 *
 * <p>一致性包：人物/道具/场景三类资产的「定妆档案」（主参考图/图集/标准描述/参数基线），
 * 落 {@code content.consistency}；保存=产新版本（一致性变更是版本事件，可追溯）。
 *
 * <p>权限：写（建版/存包）requireWrite；读（列表/单取）loadAccessible（viewer 可读）。
 *
 * <p>可观测性：建版/存包打日志（assetId/version/userId）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetVersionService {

    /** content JSON 中一致性包的键。 */
    public static final String CONSISTENCY_KEY = "consistency";

    private final AssetMapper assetMapper;
    private final AssetVersionMapper versionMapper;
    private final AssetAclService aclService;
    private final ObjectMapper objectMapper;

    /** 版本时间线（meta only，倒序）。loadAccessible（viewer 可读）。 */
    public List<VersionVO> listVersions(Long assetId, Long userId, boolean admin) {
        Asset asset = loadAsset(assetId);
        aclService.loadAccessible(asset.getProjectId(), userId, admin);
        List<AssetVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<AssetVersion>()
                .eq(AssetVersion::getAssetId, assetId));
        return versions.stream()
                .sorted(Comparator.comparingInt(AssetVersion::getVersion).reversed())
                .map(v -> toVO(v, false))
                .collect(Collectors.toList());
    }

    /** 单取某版本（带 content/fileId）。loadAccessible。 */
    public VersionVO getVersion(Long assetId, Integer version, Long userId, boolean admin) {
        Asset asset = loadAsset(assetId);
        aclService.loadAccessible(asset.getProjectId(), userId, admin);
        AssetVersion v = versionMapper.selectOne(new LambdaQueryWrapper<AssetVersion>()
                .eq(AssetVersion::getAssetId, assetId)
                .eq(AssetVersion::getVersion, version));
        if (v == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "版本不存在");
        }
        return toVO(v, true);
    }

    /**
     * 新建版本（自动版本号，乐观锁）。requireWrite。
     * 文本类：content 必填；文件类：fileId 必填（复用 stored_files 不复制文件）。
     *
     * @return 新版本号
     * @throws BusinessException 并发撞号 → CONFLICT（提示重试）
     */
    @Transactional
    public int createVersion(Long assetId, Long userId, boolean admin, VersionCreateRequest req) {
        Asset asset = loadAsset(assetId);
        aclService.requireWrite(asset.getProjectId(), userId, admin);
        boolean textType = isTextType(asset.getMediaType());
        String content = req.getContent();
        String fileId = req.getFileId();
        boolean hasContent = content != null && !content.isBlank();
        boolean hasFile = fileId != null && !fileId.isBlank();
        if (textType) {
            if (!hasContent) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文本类资产正文不能为空");
            }
        } else {
            // 文件类：换文件（fileId）或更新一致性包（content）至少一项；两者皆空才拒
            if (!hasFile && !hasContent) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文件类资产换版须提供 fileId 或正文");
            }
        }
        int expected = asset.getCurrentVersion() == null ? 1 : asset.getCurrentVersion();
        // 乐观锁：current_version 匹配才 +1
        int rows = assetMapper.bumpVersionOptimistic(assetId, expected, userId);
        if (rows == 0) {
            log.warn("version conflict: assetId={} expected={} userId={}", assetId, expected, userId);
            throw new BusinessException(ErrorCode.CONFLICT, "版本并发冲突，请刷新后重试");
        }
        int newVer = expected + 1;
        AssetVersion v = new AssetVersion();
        v.setAssetId(assetId);
        v.setVersion(newVer);
        v.setContent(hasContent ? content : "{}");
        v.setFileId(fileId);
        v.setChangeNote(req.getChangeNote());
        v.setCreatedBy(userId);
        versionMapper.insert(v);
        // 同步 assets.content = 最新正文（文本类正文 / 文件类一致性包都走 content）
        if (hasContent) {
            assetMapper.updateContent(assetId, content, userId);
        }
        log.info("version created: assetId={} version={} userId={}", assetId, newVer, userId);
        return newVer;
    }

    /**
     * 保存一致性包（局部更新，null=不改）。requireWrite。产新版本。
     *
     * @return 新版本号
     */
    @Transactional
    public int saveConsistencyPack(Long assetId, Long userId, boolean admin,
                                   com.superprogrammer.asset.dto.ConsistencyPackRequest req) {
        Asset asset = loadAsset(assetId);
        aclService.requireWrite(asset.getProjectId(), userId, admin);
        String merged = mergeConsistencyPack(asset.getContent(), req);
        VersionCreateRequest vReq = new VersionCreateRequest();
        vReq.setContent(merged);
        vReq.setChangeNote("更新一致性包");
        return createVersion(assetId, userId, admin, vReq);
    }

    /**
     * 合并一致性包字段进 content JSON（保留既有其他键，如提示词正文/剧本分场）。
     * 局部更新：字段 null=不改；gallery 空列表=清空；standardDescription 空串=清空。
     */
    String mergeConsistencyPack(String rawContent, com.superprogrammer.asset.dto.ConsistencyPackRequest req) {
        try {
            ObjectNode root = (rawContent == null || rawContent.isBlank())
                    ? objectMapper.createObjectNode()
                    : (ObjectNode) objectMapper.readTree(rawContent);
            ObjectNode pack = root.has(CONSISTENCY_KEY) && root.get(CONSISTENCY_KEY).isObject()
                    ? (ObjectNode) root.get(CONSISTENCY_KEY)
                    : objectMapper.createObjectNode();
            if (req.getMainRefImageFileId() != null) {
                pack.put("mainRefImageFileId", req.getMainRefImageFileId());
            }
            if (req.getGalleryFileIds() != null) {
                pack.set("galleryFileIds", objectMapper.valueToTree(req.getGalleryFileIds()));
            }
            if (req.getStandardDescription() != null) {
                pack.put("standardDescription", req.getStandardDescription());
            }
            if (req.getParamBaseline() != null) {
                pack.put("paramBaseline", req.getParamBaseline());
            }
            root.set(CONSISTENCY_KEY, pack);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("merge consistency pack failed assetId: {}", e.getMessage());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "一致性包合并失败：content 非合法 JSON");
        }
    }

    private boolean isTextType(String mediaType) {
        return Asset.MEDIA_PROMPT.equals(mediaType) || Asset.MEDIA_SCRIPT.equals(mediaType);
    }

    private Asset loadAsset(Long assetId) {
        Asset a = assetMapper.selectById(assetId);
        if (a == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资产不存在");
        }
        return a;
    }

    private VersionVO toVO(AssetVersion v, boolean withContent) {
        return VersionVO.builder()
                .id(v.getId())
                .assetId(v.getAssetId())
                .version(v.getVersion())
                .fileId(v.getFileId())
                .changeNote(v.getChangeNote())
                .content(withContent ? v.getContent() : null)
                .createdBy(v.getCreatedBy())
                .createdAt(v.getCreatedAt())
                .build();
    }
}
