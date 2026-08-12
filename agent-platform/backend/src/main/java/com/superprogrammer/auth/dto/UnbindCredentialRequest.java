// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/UnbindCredentialRequest.java
package com.superprogrammer.auth.dto;

import com.superprogrammer.auth.entity.UserCredential;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 解绑凭证请求（设置页「解绑」）。
 *
 * <p>credentialType 受限：PASSWORD 不可解绑（用改密）；当前支持解绑 EMAIL/PHONE/WECHAT/DINGTALK。
 */
@Data
public class UnbindCredentialRequest {

    /**
     * 凭证类型：{@link UserCredential#TYPE_EMAIL}/{@link UserCredential#TYPE_PHONE}/
     * {@link UserCredential#TYPE_WECHAT}/{@link UserCredential#TYPE_DINGTALK}。
     */
    @NotBlank(message = "凭证类型不能为空")
    @Pattern(regexp = "EMAIL|PHONE|WECHAT|DINGTALK", message = "凭证类型不合法（PASSWORD 不可解绑）")
    private String credentialType;
}
