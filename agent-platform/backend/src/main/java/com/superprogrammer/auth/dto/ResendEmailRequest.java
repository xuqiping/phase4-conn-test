// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/ResendEmailRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 重发验证邮件请求。
 */
@Data
public class ResendEmailRequest {

    /** 邮箱地址。 */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;
}
