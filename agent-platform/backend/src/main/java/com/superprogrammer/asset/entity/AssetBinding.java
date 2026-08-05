package com.superprogrammer.asset.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资产↔画布 双向追溯台账（asset_bindings，V59）。
 *
 * <p>双向绑定（设计方案 §八"双向追溯"）：
 * <ul>
 *   <li>{@link #BIND_PRODUCED} PRODUCED：资产产自哪个画布节点（画布入库时落，捕获生成谱系）</li>
 *   <li>{@link #BIND_REFERENCE} REFERENCE：资产被哪个画布节点引用（库→画布引用时落，记录引用版本快照）</li>
 * </ul>
 *
 * <p>资产详情页"使用记录"= by assetId；画布节点徽标"来自资产·xx v2"= by canvasId+nodeId。
 * 不加 FK 到 canvases（跨包解耦，canvas 零回归，设计方案 §十四）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "asset_bindings", autoResultMap = true)
public class AssetBinding extends BaseEntity {

    /** 绑定类型：资产被画布节点引用。 */
    public static final String BIND_REFERENCE = "REFERENCE";
    /** 绑定类型：资产产自画布节点（入库时落）。 */
    public static final String BIND_PRODUCED = "PRODUCED";

    /** 所属资产（FK assets）。 */
    private Long assetId;

    /** 引用/产出的版本快照号（锁定版本，可空=未版本化时）。 */
    private Integer assetVersion;

    /** 关联画布（canvases.id，不加 FK 跨包解耦）。 */
    private Long canvasId;

    /** 关联画布节点 id（节点内唯一标识）。 */
    private String nodeId;

    /** 绑定类型：REFERENCE/PRODUCED。 */
    private String bindType;
}
