// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/ChangePasswordRequest.java
package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求（设置页「修改密码」，需登录态）。
 *
 * <p>与找回密码的区别：改密需验旧密码（已登录场景），找回密码需验重置 token（未登录场景）。
 * 两者共同点：PasswordPolicy + 新旧不同 + 改完踢所有会话。
 */
@Data
public class ChangePasswordRequest {

    /** 当前密码（明文，校验后即弃）。 */
    @NotBlank(message = "请输入当前密码")
    private String oldPassword;

    /** 新密码（强度由 PasswordPolicy 兜底：6-100 + 大小写/数字/特殊字符 + 非弱密码字典）。 */
    @NotBlank(message = "请输入新密码")
    @Size(min = 6, max = 100, message = "密码长度必须在6-100之间")
    private String newPassword;
}
