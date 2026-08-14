// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/MfaVerifyRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 安全体系 S5 · SEC-FR-006（A6 TOTP）：两步登录第二屏提交。
 * mfaToken=密码步通过后签发的 5 分钟中间票；code=6 位 TOTP 码或一次性恢复码。
 */
@Data
public class MfaVerifyRequest {

    @NotBlank(message = "MFA会话令牌不能为空")
    private String mfaToken;

    @NotBlank(message = "验证码不能为空")
    private String code;
}
