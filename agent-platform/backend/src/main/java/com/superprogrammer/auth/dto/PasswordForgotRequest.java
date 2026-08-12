// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/PasswordForgotRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发起找回密码请求。
 */
@Data
public class PasswordForgotRequest {

    /** 账号标识（用户名/邮箱/手机号）。 */
    @NotBlank(message = "账号不能为空")
    private String identifier;

    /** 渠道：EMAIL 或 SMS（默认 EMAIL）。 */
    @Pattern(regexp = "EMAIL|SMS", message = "渠道必须是 EMAIL 或 SMS")
    private String channel = "EMAIL";
}
