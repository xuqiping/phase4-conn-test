package com.superprogrammer.asset.dto;

import lombok.Data;

/** 普通 OWNER 发布到公众池时选择的访问模式。管理员请求会被服务端强制为 OPEN。 */
@Data
public class PublicPublishRequest {
    private String accessMode;

    /**
     * 2x 待决策项（V100）：是否允许公共用户复制资产（null=不改，沿用当前值/默认 TRUE）。
     * 管理员代发同样可设；跨 unpublish→再发布保留。
     */
    private Boolean allowPublicCopy;
}
