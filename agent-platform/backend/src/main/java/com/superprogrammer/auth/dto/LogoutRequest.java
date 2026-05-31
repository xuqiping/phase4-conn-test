// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/LogoutRequest.java
package com.superprogrammer.auth.dto;

import lombok.Data;

@Data
public class LogoutRequest {

    /**
     * Refresh Token，登出时加入黑名单
     */
    private String refreshToken;
}
