package com.superprogrammer.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.asset.dto.AssetUsageVO;
import com.superprogrammer.asset.entity.AssetBinding;
import com.superprogrammer.asset.mapper.AssetBindingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 项目资产库·资产↔画布 绑定台账（plan §S7 / FR-008/009/011，设计方案 §八「双向追溯」）。
 *
 * <p>asset_bindings 表的写读侧，独立于资产 CRUD，供画布打通（AssetCanvasBridgeService）调用：
 * <ul>
 *   <li>{@link #recordProduced}：画布产出入库时落 PRODUCED 绑定（捕获资产产自哪个节点）</li>
 *   <li>{@link #recordReference}：库→画布引用时落 REFERENCE 绑定（记录被哪个节点引用、锁版本快照）</li>
 *   <li>{@link #findProduced}：重复入库检测（节点已有 PRODUCED 绑定？plan L5）</li>
 *   <li>{@link #listUsages}：资产详情页「使用记录」（by assetId）</li>
 * </ul>
 *
 * <p>权限咽喉点在调用方（BridgeService / Controller 过 Acl），本服务只管台账写读，
 * 不重复判权限（避免双重检查耦合）。绑定记录软删留存历史（引用快照语义，设计方案 §六）。
 *
 * <p>可观测性：绑定写入打日志（assetId/canvasId/nodeId/bindType）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetBindingService {

    private final AssetBindingMapper bindingMapper;

    /**
     * 落 PRODUCED 绑定：资产产自画布节点（画布入库时调）。
     *
     * @param assetId       资产 id
     * @param assetVersion  版本快照号（可空）
     * @param canvasId      画布 id
     * @param nodeId        节点 id
     * @param userId        操作人（审计）
     * @return 绑定记录 id
     */
    public Long recordProduced(Long assetId, Integer assetVersion, Long canvasId, String nodeId, Long userId) {
        AssetBinding b = newBinding(assetId, assetVersion, canvasId, nodeId, AssetBinding.BIND_PRODUCED, userId);
        bindingMapper.insert(b);
        log.info("asset binding PRODUCED: assetId={} v={} canvasId={} nodeId={} userId={}",
                assetId, assetVersion, canvasId, nodeId, userId);
        return b.getId();
    }

    /**
     * 落 REFERENCE 绑定：资产被画布节点引用（库→画布引用时调，锁版本快照）。
     *
     * @return 绑定记录 id
     */
    public Long recordReference(Long assetId, Integer assetVersion, Long canvasId, String nodeId, Long userId) {
        AssetBinding b = newBinding(assetId, assetVersion, canvasId, nodeId, AssetBinding.BIND_REFERENCE, userId);
        bindingMapper.insert(b);
        log.info("asset binding REFERENCE: assetId={} v={} canvasId={} nodeId={} userId={}",
                assetId, assetVersion, canvasId, nodeId, userId);
        return b.getId();
    }

    /**
     * 重复入库检测：该画布节点是否已有 PRODUCED 绑定（plan L5）。
     *
     * @return 已有绑定则返回（含 assetId 供「存为新版本」），否则 null
     */
    public AssetBinding findProduced(Long canvasId, String nodeId) {
        if (canvasId == null || nodeId == null) {
            return null;
        }
        return bindingMapper.selectOne(new LambdaQueryWrapper<AssetBinding>()
                .eq(AssetBinding::getCanvasId, canvasId)
                .eq(AssetBinding::getNodeId, nodeId)
                .eq(AssetBinding::getBindType, AssetBinding.BIND_PRODUCED)
                .last("LIMIT 1"));
    }

    /**
     * 资产「使用记录」（资产详情页，by assetId）。倒序——最新绑定在前。
     * 不过滤 bind_type：PRODUCED + REFERENCE 都列出（产自 + 被引用）。
     */
    public List<AssetUsageVO> listUsages(Long assetId) {
        return bindingMapper.selectList(new LambdaQueryWrapper<AssetBinding>()
                        .eq(AssetBinding::getAssetId, assetId)
                        .orderByDesc(AssetBinding::getCreatedAt))
                .stream()
                .map(AssetBindingService::toVO)
                .collect(Collectors.toList());
    }

    private AssetBinding newBinding(Long assetId, Integer assetVersion, Long canvasId, String nodeId,
                                    String bindType, Long userId) {
        AssetBinding b = new AssetBinding();
        b.setAssetId(assetId);
        b.setAssetVersion(assetVersion);
        b.setCanvasId(canvasId);
        b.setNodeId(nodeId);
        b.setBindType(bindType);
        b.setCreatedBy(userId);
        return b;
    }

    private static AssetUsageVO toVO(AssetBinding b) {
        return AssetUsageVO.builder()
                .id(b.getId())
                .assetVersion(b.getAssetVersion())
                .canvasId(b.getCanvasId())
                .nodeId(b.getNodeId())
                .bindType(b.getBindType())
                .createdBy(b.getCreatedBy())
                .createdAt(b.getCreatedAt())
                .build();
    }
}
