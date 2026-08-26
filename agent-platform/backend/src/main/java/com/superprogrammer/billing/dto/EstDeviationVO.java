package com.superprogrammer.billing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * D9（V160）：TOKEN est 预估秒价近 7 天偏差（实耗 vs 预估，管理员校准 est 用）。
 *
 * <p>口径：近 7 天 SUCCEEDED 视频任务，Σ实耗积分 / Σ预估积分 − 1，按
 * (providerId, model, hasReference) 聚合；样本 &lt; 3 不出行（噪音防护）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EstDeviationVO {
    private Long providerId;
    private String model;
    private Boolean hasReference;
    /** 偏差百分比，正=实耗偏高（est 偏低需上调），负=实耗偏低；四舍五入整数 */
    private Integer deviationPct;
    /** 近 7 天样本数（SUCCEEDED 且实耗&gt;0 的任务数） */
    private Integer sampleCount;
}
