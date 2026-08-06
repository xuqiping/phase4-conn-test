package com.superprogrammer.asset.dto;

import lombok.Data;

/**
 * 一键分镜请求（plan §S19 / 1_8.6计划 第 11 点）。
 *
 * <p>对剧本资产调 LLM 拆镜头，每镜头产一个分镜资产（字段 1/2 自动填，含实体→资产首轮匹配）。
 * {@link #model} 缺省走 {@code asset.script-model} 配置。
 */
@Data
public class StoryboardBreakdownRequest {

    /** 指定模型（可空=用默认 doubao-seed-2.0-code）。 */
    private String model;
}
