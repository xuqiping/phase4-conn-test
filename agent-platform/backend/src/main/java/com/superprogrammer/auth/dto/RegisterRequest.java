// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/RegisterRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度必须在3-50之间")
    private String username;

    /** 17x：昵称/姓名（users.name，必填——项目组/账单/充值下拉等处展示用）。 */
    @NotBlank(message = "昵称/姓名不能为空")
    @Size(max = 32, message = "昵称/姓名最长 32 字")
    private String name;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 100, message = "密码长度必须在6-100之间")
    private String password;

    /** 12x 开关回退：邮箱验证总开关开时必填（服务端判）；关时可选填。 */
    @Email(message = "邮箱格式不正确")
    @Size(max = 100)
    private String email;

    /** 12x B1：注册邮箱验证码（先调 /api/auth/register/email-code 获取）。
     *  邮箱验证总开关开时必填（服务端判格式/有效性）；关时忽略。 */
    @Size(max = 6)
    private String emailCode;

    /** 12x B2：同 IP 连续失败 ≥2 次后必填（AJ-Captcha 滑块 token，单次有效）。 */
    @Size(max = 2048)
    private String captchaVerification;

    /** 协议勾选（合规要求：注册前必须同意《用户协议》《隐私政策》）。 */
    @AssertTrue(message = "请先阅读并同意《用户协议》和《隐私政策》")
    private Boolean agreeTerms;
}
