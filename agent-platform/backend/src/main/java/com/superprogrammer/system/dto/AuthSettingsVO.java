package com.superprogrammer.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthSettingsVO {
    private Long accessTokenExpirationMs;

    /** 安全体系 S2 · A8（SEC-FR-008）：单点登录开关（同账号仅一处在线，新登录踢旧会话）。 */
    private Boolean singleSessionEnabled;
}
