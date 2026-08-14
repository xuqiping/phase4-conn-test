package com.superprogrammer.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 注销账号请求（安全体系 S5 · SEC-FR-100 J2，需登录态）。
 *
 * <p>密码确认是注销的最后一道闸——防止共享电脑上「忘关标签页被一键注销」。
 * 注销=软删匿名化：身份字段覆盖为 deleted_{uuid}、状态 DELETED、全会话踢除；
 * 计费流水/审计日志按法定口径保留（口径见隐私政策页）。
 */
@Data
public class DeleteAccountRequest {

    /** 当前密码（明文，校验后即弃）。 */
    @NotBlank(message = "请输入当前密码确认注销")
    private String password;

    /** 当前 refresh token（可选）：注销时一并拉黑，立即失效不等自然过期。 */
    private String refreshToken;
}
