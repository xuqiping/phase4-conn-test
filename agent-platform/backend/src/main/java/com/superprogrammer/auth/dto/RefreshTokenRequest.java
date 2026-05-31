// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/RefreshTokenRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshTokenRequest {

    @NotBlank(message = "refreshToken不能为空")
    private String refreshToken;
}
