// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/MfaConfirmRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 安全体系 S5 · SEC-FR-006（A6 TOTP）：绑定确认/解绑共用的验证码提交体。
 */
@Data
public class MfaConfirmRequest {

    /** 6 位 TOTP 码或一次性恢复码 */
    @NotBlank(message = "验证码不能为空")
    private String code;
}
