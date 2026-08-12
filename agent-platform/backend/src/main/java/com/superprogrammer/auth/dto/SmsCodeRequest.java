// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/SmsCodeRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发短信验证码请求。
 */
@Data
public class SmsCodeRequest {

    /** 手机号（国内号段）。 */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 滑块验证码 token（AJ-Captcha，前置闸门）。 */
    @NotBlank(message = "请先完成滑块验证")
    private String captchaToken;
}
