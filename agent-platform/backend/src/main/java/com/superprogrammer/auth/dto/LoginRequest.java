// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/LoginRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /** 12x B2：同账号连续失败 ≥2 次后必填（AJ-Captcha 滑块 token，单次有效）。 */
    @Size(max = 2048)
    private String captchaVerification;
}
