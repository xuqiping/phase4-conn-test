package com.superprogrammer.asset.dto;

import lombok.Data;

/**
 * 剧本拆分场请求（plan §S6 / FR-010）。
 *
 * <p>对剧本资产调 LLM 拆 3-10 个分场，结果落入 {@code content.scenes} 并产新版本。
 * {@link #model} 缺省走 {@code asset.script-model} 配置。
 */
@Data
public class ScriptBreakdownRequest {

    /** 指定模型（可空=用默认 doubao-seed-2.0-code）。 */
    private String model;
}
