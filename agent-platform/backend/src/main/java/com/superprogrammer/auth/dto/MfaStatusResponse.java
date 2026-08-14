// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/MfaStatusResponse.java
package com.superprogrammer.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 安全体系 S5 · SEC-FR-006（A6 TOTP）：当前用户 MFA 绑定状态（设置页/登录引导用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaStatusResponse {

    /** 是否已绑定 TOTP（已绑定 = 登录恒走两步） */
    private boolean bound;

    /** security.auth.totp.required 开关当前值（开 = 平台建议所有 admin 绑定） */
    private boolean required;
}
