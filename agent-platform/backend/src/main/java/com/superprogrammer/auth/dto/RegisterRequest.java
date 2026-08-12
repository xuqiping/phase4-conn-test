// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/RegisterRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度必须在3-50之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 100, message = "密码长度必须在6-100之间")
    private String password;

    @Email(message = "邮箱格式不正确")
    private String email;

    /** 协议勾选（合规要求：注册前必须同意《用户协议》《隐私政策》）。 */
    @AssertTrue(message = "请先阅读并同意《用户协议》和《隐私政策》")
    private Boolean agreeTerms;
}
