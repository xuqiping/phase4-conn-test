package com.superprogrammer.auth.dingtalk.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DingTalkLoginRequest {

    /** 钉钉免登授权码 authCode（前端从钉钉授权回调 URL 取） */
    @NotBlank(message = "authCode 不能为空")
    private String authCode;

    /**
     * 授权码来源，决定后端换码链路：
     * <ul>
     *   <li><b>jsapi</b>：容器内 dd.runtime.permission.requestAuthCode 拿的免登码 → 走 oapi 老链路（企业内部应用）</li>
     *   <li><b>oauth2</b>（默认）：login.dingtalk.com OAuth2 网页授权码 → 走 /v1.0/oauth2/userAccessToken</li>
     * </ul>
     */
    private String source;
}
