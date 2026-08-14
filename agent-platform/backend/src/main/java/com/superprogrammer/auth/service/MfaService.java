// agent-platform/backend/src/main/java/com/superprogrammer/auth/service/MfaService.java
package com.superprogrammer.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.auth.dto.MfaBindResponse;
import com.superprogrammer.auth.totp.TotpService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全体系 S5 · SEC-FR-006（A6 管理员 TOTP）：绑定/校验/恢复码状态管理。
 *
 * <p>存储走 system_settings 通用加密 KV（AES 加密落库，key 带用户 id 隔离）：
 * secret 与恢复码哈希都不落明文；恢复码 JSON 数组只存 SHA-256 哈希（用一张废一张）。
 * 该组 key 不在 RuleConfigController EDITABLE_KEYS 内——管理端改不了别人的 secret。</p>
 *
 * <p>降级红线（检测层不自残）：TOTP 校验链路任何存储/解析异常 → 记 ERROR 日志后按
 * 「验证失败」处理（拒绝登录比放行安全，MFA 是显式开启的安全能力，不适用降级放行）；
 * 但绑定流异常直接抛业务异常，无歧义。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaService {

    private static final String SECRET_KEY_PREFIX = "security.totp.secret.u.";
    private static final String PENDING_KEY_PREFIX = "security.totp.pending.u.";
    private static final String RECOVERY_KEY_PREFIX = "security.totp.recovery.u.";
    private static final String OTPAUTH_ISSUER = "AgentPlatform";

    private final SystemSettingService systemSettingService;
    private final TotpService totpService;
    private final ObjectMapper objectMapper;

    /** 是否已绑定（secret 存在且非空）。登录流据此分流两步登录。 */
    public boolean isBound(Long userId) {
        try {
            String secret = systemSettingService.getDecryptedValue(SECRET_KEY_PREFIX + userId);
            return secret != null && !secret.isBlank();
        } catch (Exception e) {
            log.error("TOTP isBound 读取失败(按未绑定处理,登录走单步+绑定页可见) userId={} : {}", userId, e.getMessage());
            return false;
        }
    }

    /** 发起绑定：生成新 secret 存 pending（确认前不生效），返回 secret + otpauth URI。 */
    public MfaBindResponse startBind(Long userId, String username) {
        if (isBound(userId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "已绑定TOTP，请先解绑后再重新绑定");
        }
        String secret = totpService.generateSecret();
        systemSettingService.upsertEncrypted(PENDING_KEY_PREFIX + userId, secret,
                "TOTP绑定中secret(确认后转正) u" + userId);
        log.info("TOTP绑定发起 userId={} username={}", userId, username);
        return MfaBindResponse.builder()
                .secret(secret)
                .otpauthUri(totpService.buildOtpauthUri(secret, username == null ? String.valueOf(userId) : username, OTPAUTH_ISSUER))
                .build();
    }

    /**
     * 确认绑定：用验证器 App 首个 code 验 pending secret → 转正 + 发放 8 组一次性恢复码。
     * 恢复码明文仅本次返回，服务端只存 SHA-256 哈希数组。
     */
    public MfaBindResponse confirmBind(Long userId, String code) {
        String pending = systemSettingService.getDecryptedValue(PENDING_KEY_PREFIX + userId);
        if (pending == null || pending.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请先调用绑定接口获取secret");
        }
        if (!totpService.verify(pending, code, System.currentTimeMillis())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证码错误，请确认验证器时间同步后重试");
        }
        systemSettingService.upsertEncrypted(SECRET_KEY_PREFIX + userId, pending,
                "TOTP生效secret u" + userId);
        systemSettingService.clearSettingValue(PENDING_KEY_PREFIX + userId);

        List<String> codes = totpService.generateRecoveryCodes();
        saveRecoveryHashes(userId, codes);
        log.info("TOTP绑定确认成功 userId={}", userId);
        return MfaBindResponse.builder().recoveryCodes(codes).build();
    }

    /**
     * 解绑：需提供当前有效 TOTP 码或恢复码（防会话被劫后直接拆掉第二因素）。
     */
    public void unbind(Long userId, String code) {
        if (!isBound(userId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未绑定TOTP");
        }
        if (!verifyAndConsume(userId, code, false)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证码错误，解绑失败");
        }
        systemSettingService.clearSettingValue(SECRET_KEY_PREFIX + userId);
        systemSettingService.clearSettingValue(RECOVERY_KEY_PREFIX + userId);
        log.info("TOTP解绑 userId={}", userId);
    }

    /**
     * 注销清痕（安全体系 S5 · SEC-FR-100 J2）：无验证码直接清除全部 TOTP 材料。
     * 与 unbind 不同——注销前已做「登录态+密码」二次确认且账号随之软删，
     * 不存在「劫会话拆第二因素」的攻击面（账号本身已不存在）。
     */
    public void purgeForDeletedUser(Long userId) {
        systemSettingService.clearSettingValue(SECRET_KEY_PREFIX + userId);
        systemSettingService.clearSettingValue(RECOVERY_KEY_PREFIX + userId);
        systemSettingService.clearSettingValue(PENDING_KEY_PREFIX + userId);
    }

    /**
     * 登录第二屏校验：TOTP 码（±1 窗口）或一次性恢复码（命中即作废）。
     *
     * @param consumeRecovery true=恢复码命中后从存储移除（登录/解绑场景）；
     *                        false 仅校验不消耗（内部复用）
     */
    public boolean verifyAndConsume(Long userId, String code, boolean consumeRecovery) {
        try {
            String secret = systemSettingService.getDecryptedValue(SECRET_KEY_PREFIX + userId);
            if (secret == null || secret.isBlank()) {
                return false;
            }
            if (totpService.verify(secret, code, System.currentTimeMillis())) {
                return true;
            }
            // 恢复码：SHA-256 后与存储哈希比对，命中移除（一码一次）
            String codeHash = totpService.sha256Hex((code == null ? "" : code.trim()).toLowerCase());
            List<String> hashes = loadRecoveryHashes(userId);
            if (hashes != null && hashes.contains(codeHash)) {
                if (consumeRecovery) {
                    hashes.remove(codeHash);
                    persistRecoveryHashes(userId, hashes);
                    log.info("TOTP恢复码已消耗 userId={} 剩余={}", userId, hashes.size());
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("TOTP校验链路异常(按验证失败处理,不放行) userId={} : {}", userId, e.getMessage());
            return false;
        }
    }

    // ---------- 内部工具 ----------

    private List<String> loadRecoveryHashes(Long userId) {
        try {
            String json = systemSettingService.getDecryptedValue(RECOVERY_KEY_PREFIX + userId);
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (Exception e) {
            log.error("恢复码哈希读取失败(视为无恢复码,仅TOTP码可用) userId={} : {}", userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void persistRecoveryHashes(Long userId, List<String> hashes) {
        try {
            systemSettingService.upsertEncrypted(RECOVERY_KEY_PREFIX + userId,
                    objectMapper.writeValueAsString(hashes), "TOTP恢复码SHA256哈希数组 u" + userId);
        } catch (Exception e) {
            log.error("恢复码哈希写回失败 userId={} : {}", userId, e.getMessage());
        }
    }

    private void saveRecoveryHashes(Long userId, List<String> codes) {
        List<String> hashes = new ArrayList<>(codes.size());
        for (String c : codes) {
            hashes.add(totpService.sha256Hex(c));
        }
        persistRecoveryHashes(userId, hashes);
    }
}
