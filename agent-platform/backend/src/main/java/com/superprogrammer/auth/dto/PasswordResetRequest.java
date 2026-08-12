// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/PasswordResetRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置密码请求。
 */
@Data
public class PasswordResetRequest {

    /** 重置 token（邮件链接）或短信码（channel=SMS）。 */
    @NotBlank(message = "重置凭证不能为空")
    private String token;

    /** 新密码。 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 100, message = "密码长度必须在6-100之间")
    private String newPassword;

    /** 渠道：EMAIL 或 SMS（默认 EMAIL）。 */
    @Pattern(regexp = "EMAIL|SMS", message = "渠道必须是 EMAIL 或 SMS")
    private String channel = "EMAIL";

    /** 手机号（channel=SMS 时必填，用于匹配重置码）。 */
    private String phone;
}
