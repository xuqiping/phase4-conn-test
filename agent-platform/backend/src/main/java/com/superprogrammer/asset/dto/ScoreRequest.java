package com.superprogrammer.asset.dto;

import lombok.Data;

/**
 * 提交评分请求（2x第三轮C6，POST /api/assets/assets/{id}/score）。
 */
@Data
public class ScoreRequest {

    /** 百分制 0-100（服务校验 + DB ck_asset_score_range 双保险）。 */
    private Integer score;
}
