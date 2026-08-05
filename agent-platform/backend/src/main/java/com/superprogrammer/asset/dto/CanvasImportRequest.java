package com.superprogrammer.asset.dto;

import lombok.Data;

import java.util.List;

/**
 * 画布产出入库请求（plan §S7 / FR-008，设计方案 §八「画布→库」）。
 *
 * <p>画布节点右键「存入资产库」→ 弹窗选项目/叙事角色/命名 → 调本端点。
 * 后端按 {@link #canvasId}+{@link #nodeId} 从画布快照定位节点，据节点类型自动映射为对应资产类型，
 * 捕获生成谱系（prompt/model/upstream）入 gen_meta，落 PRODUCED 绑定（双向追溯）。
 *
 * <p>重复入库（plan L5）：该节点已有 PRODUCED 绑定时，{@link #mode} 控制行为：
 * <ul>
 *   <li>缺省/空 → 不创建，返回 duplicateAssetId 供前端提示「存为新版本/新建」</li>
 *   <li>{@code NEW_VERSION} → 在已入库资产上建新版本（变体归组，推荐）</li>
 *   <li>{@code NEW_ASSET} → 另起资产（同节点产多资产）</li>
 * </ul>
 */
@Data
public class CanvasImportRequest {

    /** 画布 id（入库源，落 PRODUCED 绑定 canvas_id）。 */
    private Long canvasId;

    /** 节点 id（画布内唯一，入库源 node_id）。 */
    private String nodeId;

    /** 目标项目 id（授权边界 + 落格位置）。 */
    private Long projectId;

    /** 叙事角色 key 列表（受控词汇，多对多挂载）。 */
    private List<String> roleKeys;

    /** 资产名（缺省用节点产出派生名）。 */
    private String name;

    /** 描述。 */
    private String description;

    /** 标签。 */
    private List<String> tags;

    /**
     * 重复入库处理模式：NEW_VERSION（存为新版本，默认建议）/ NEW_ASSET（另起资产）。
     * 空=自动检测，遇已有 PRODUCED 绑定则不创建、返回 duplicate 提示。
     */
    private String mode;
}
