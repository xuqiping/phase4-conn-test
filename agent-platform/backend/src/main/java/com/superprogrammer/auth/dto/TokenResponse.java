// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/TokenResponse.java
package com.superprogrammer.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Long expiresIn;
    private UserInfo userInfo;

    /**
     * 安全体系 S5 · SEC-FR-006（A6 TOTP）：true=已绑定用户密码步通过，进入第二屏
     * （此响应无 accessToken/refreshToken，只有 mfaToken）。null/未绑定 = 正常单步登录。
     */
    private Boolean mfaRequired;
    /** 两步登录中间票（5 分钟一次性，仅配合 POST /auth/mfa/verify 使用） */
    private String mfaToken;
    /** totp.required 开（灰度）且当前 admin 未绑定 → true（前端引导绑定，不阻断登录） */
    private Boolean mfaBindAdvice;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserInfo {
        private Long id;
        private String username;
        private String name;
        private String primaryDepartmentName;
        private String email;
        private String avatar;
        private List<String> roles;
        private List<String> permissions;
    }
}
