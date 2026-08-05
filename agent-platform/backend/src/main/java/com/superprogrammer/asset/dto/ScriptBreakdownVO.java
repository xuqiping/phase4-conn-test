package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 剧本拆分场结果（plan §S6 / FR-010）。 */
@Data
@Builder
public class ScriptBreakdownVO {

    /** 拆出的分场列表。 */
    private List<SceneVO> scenes;

    /** 实际使用的模型。 */
    private String model;

    /** 本次拆分产出的新版本号。 */
    private Integer version;
}
