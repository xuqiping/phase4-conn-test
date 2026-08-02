// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/LoginRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
