// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/MfaBindResponse.java
package com.superprogrammer.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 安全体系 S5 · SEC-FR-006（A6 TOTP）绑定响应。
 * start：返回 secret + otpauth URI（前端展示，用户手动加入验证器 App）。
 * confirm：返回 8 组一次性恢复码（明文仅此一次，服务端只存 SHA-256 哈希）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaBindResponse {

    /** 绑定阶段：Base32 secret（用户可手动输入验证器 App） */
    private String secret;

    /** 绑定阶段：otpauth:// 标准绑定 URI（可粘贴到验证器 App） */
    private String otpauthUri;

    /** 确认阶段：8 组一次性恢复码（仅确认成功返回一次） */
    private java.util.List<String> recoveryCodes;
}
