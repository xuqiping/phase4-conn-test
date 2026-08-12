// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/EmailVerifyRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 邮箱激活请求（激活链接落地页提交）。
 */
@Data
public class EmailVerifyRequest {

    /** 激活 token（从激活链接 query 参数取）。 */
    @NotBlank(message = "激活 token 不能为空")
    @Size(max = 200, message = "激活 token 过长")
    private String token;
}
