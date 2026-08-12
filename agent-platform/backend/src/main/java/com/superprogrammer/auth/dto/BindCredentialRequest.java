// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/BindCredentialRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 绑定邮箱请求（设置页「绑定邮箱」）。
 *
 * <p>绑定后凭证 verified=FALSE，激活邮件由后端 Controller 触发（复用注册激活链路）。
 */
@Data
public class BindCredentialRequest {

    /** 要绑定的邮箱（归一化为小写）。 */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 200, message = "邮箱过长")
    private String email;
}
