// agent-platform/backend/src/main/java/com/superprogrammer/llm/dto/ProviderExportRequest.java
package com.superprogrammer.llm.dto;

import lombok.Data;
import lombok.ToString;

/**
 * 供应商导出请求（10x-2；修复VIII B4 · VIII-5 改 POST + 密码二次确认）。
 *
 * <p>导出文件含明文 API Key，必须携带当前登录用户密码复验（与注销同款 BCrypt 校验）。
 * 密码走 body 绝不进 URL query（日志面）；故意不加 @NotBlank——空密码与错密码同路进
 * {@code AuthService.verifyUserPassword} 抛业务异常，让 @AuditLog 的 @Around 切面
 * 对「失败尝试」也统一落 FAIL 审计行（若被参数校验先行拦截则绕过切面、失败无痕）。
 */
@Data
public class ProviderExportRequest {

    /**
     * 当前登录密码（明文，校验后即弃）。
     * review 修复：@ToString.Exclude 防密码经 Lombok toString 落审计 detail_json
     * （LogMasker 的 password 规则带前瞻条件，无数字/含特殊字符的密码打不住码）。
     */
    @ToString.Exclude
    private String password;
}
