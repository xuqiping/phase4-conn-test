package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 媒体产物入库结果（POST /api/assets/from-media 响应）。
 *
 * <p>生图入库无画布节点绑定，不存在「重复入库三态」——同一张图可多次入库为独立资产
 * （用户可能想把同一张图存进不同项目）。故 {@link #created} 恒 true（校验通过即建），
 * 字段集是 {@link CanvasImportVO} 的无重复检测子集。
 */
@Data
@Builder
public class MediaImportVO {

    /** 是否入库成功。 */
    private boolean created;
    /** 新建资产 id。 */
    private Long assetId;
    /** 资产名。 */
    private String name;
    /** 媒体类型（图片="图片"）。 */
    private String mediaType;
    /** 版本号（首版=1）。 */
    private Integer version;
    /** 提示信息。 */
    private String message;
}
