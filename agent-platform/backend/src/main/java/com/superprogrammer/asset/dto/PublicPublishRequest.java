package com.superprogrammer.asset.dto;

import lombok.Data;

/** 普通 OWNER 发布到公众池时选择的访问模式。管理员请求会被服务端强制为 OPEN。 */
@Data
public class PublicPublishRequest {
    private String accessMode;
}
