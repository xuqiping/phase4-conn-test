package com.superprogrammer.asset.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分场条目（plan §S6 / FR-010，设计方案 §四 剧本）。
 *
 * <p>场号 + 场景/画面描述。剧本「拆分场」后落入资产 {@code content.scenes}，
 * 后续可联动画布故事板段（场号→段 label，设计方案 §八）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SceneVO {

    /** 场号（从 1 递增）。 */
    private Integer index;

    /** 场景/画面描述。 */
    private String description;
}
