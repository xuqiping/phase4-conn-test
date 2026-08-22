// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/RegisterEmailCodeRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 12x B1：注册邮箱验证码发送请求（注册前置——先证明邮箱归属，再允许建号）。
 */
@Data
public class RegisterEmailCodeRequest {

    /** 待注册邮箱。 */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;
}
