// agent-platform/backend/src/main/java/com/superprogrammer/auth/dto/CredentialVO.java
package com.superprogrammer.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 凭证视图（设置页展示用）。
 *
 * <p>identifier 已脱敏（手机号 138****8000、邮箱 a***@x.com），避免前端/日志明文回显敏感信息。
 */
@Data
@Builder
public class CredentialVO {

    /** 凭证类型：PASSWORD/EMAIL/PHONE/WECHAT/DINGTALK。 */
    private String credentialType;

    /** 脱敏后的凭证标识（展示用，非真实值）。 */
    private String identifier;

    /** 是否已验证。 */
    private Boolean verified;

    /** 首次验证通过时间。 */
    private OffsetDateTime verifiedAt;
}
