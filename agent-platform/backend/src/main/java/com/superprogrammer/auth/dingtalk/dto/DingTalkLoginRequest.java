package com.superprogrammer.auth.dingtalk.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DingTalkLoginRequest {

    /** 钉钉免登授权码 authCode（前端从钉钉授权回调 URL 取） */
    @NotBlank(message = "authCode 不能为空")
    private String authCode;
}
