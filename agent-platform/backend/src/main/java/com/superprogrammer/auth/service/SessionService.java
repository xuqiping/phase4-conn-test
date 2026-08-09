package com.superprogrammer.auth.service;

import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 单点登录会话服务（安全体系 S2 · A8，SEC-FR-008）：同账号仅一处在线，新登录踢旧会话。
 *
 * <p>模型：登录签发 sid=UUID 写 {@code session:user:{userId}}（TTL=refresh 有效期，
 * 每次登录覆盖写=踢旧）；JWT access/refresh 均带 sid claim；请求/刷新比对 sid 与 Redis
 * 当前值，不符 → 拒绝（40104 固定话术「账号已在别处登录」）。
 *
 * <p>降级原则（与 S1 防爆破一致，可用性 > 强制力）：Redis 故障 → 放行 + WARN，不杀主链。
 * <p>开关：{@code auth.single_session.enabled}（system_settings，默认开；关=恢复多会话）。
 * <p>上线一次性影响：旧 token 无 sid claim → 视为失效强制重登一次（配合 G1 轮换公告合并执行）。
 */
@Slf4j
@Service
public class SessionService {

    /** 会话键前缀。 */
    static final String SESSION_PREFIX = "session:user:";

    private final StringRedisTemplate redisTemplate;
    private final SystemSettingService systemSettingService;
    private final AuditLogService auditLogService;
    private final com.superprogrammer.auth.security.JwtUtil jwtUtil;

    public SessionService(StringRedisTemplate redisTemplate,
                          SystemSettingService systemSettingService,
                          AuditLogService auditLogService,
                          com.superprogrammer.auth.security.JwtUtil jwtUtil) {
        this.redisTemplate = redisTemplate;
        this.systemSettingService = systemSettingService;
        this.auditLogService = auditLogService;
        this.jwtUtil = jwtUtil;
    }

    public boolean isSingleSessionEnabled() {
        return systemSettingService.getBoolean(
                SystemSettingService.AUTH_SINGLE_SESSION_ENABLED, true);
    }

    /**
     * 开新会话：生成 sid 覆盖写 Redis（=踢旧）。返回 sid（签进 JWT）。
     * Redis 故障 → 返回 sid 但写不进去（降级：比对环节同样故障放行，语义自洽）。
     */
    public String newSession(Long userId, String username) {
        String sid = UUID.randomUUID().toString();
        if (!isSingleSessionEnabled()) {
            return sid;
        }
        try {
            String key = SESSION_PREFIX + userId;
            String previous = redisTemplate.opsForValue().get(key);
            redisTemplate.opsForValue().set(key, sid,
                    jwtUtil.getRefreshExpiration(), TimeUnit.MILLISECONDS);
            if (previous != null && !previous.equals(sid)) {
                // 踢旧留痕（踢的瞬间记一次，而非旧 token 每次请求都记——防审计刷量）
                auditLogService.record(auditLogService.fromMdc("auth", "session_kicked", "user",
                        String.valueOf(userId), "{\"username\":\"" + username + "\"}",
                        AuditLogEntity.RESULT_SUCCESS));
            }
        } catch (Exception e) {
            log.warn("会话写入失败(降级放行,单点登录本轮不生效) userId={} : {}", userId, e.getMessage());
        }
        return sid;
    }

    /**
     * 校验 token 的 sid 是否当前会话。false=被踢/旧 token（调用方拒绝）。
     * 开关关闭或 Redis 故障 → true（降级放行 + WARN）。
     */
    public boolean isCurrent(Long userId, String sid) {
        if (!isSingleSessionEnabled()) {
            return true;
        }
        if (sid == null) {
            return false; // 旧无 sid token：上线一次性强制重登
        }
        try {
            String current = redisTemplate.opsForValue().get(SESSION_PREFIX + userId);
            return sid.equals(current);
        } catch (Exception e) {
            log.warn("会话比对失败(降级放行) userId={} : {}", userId, e.getMessage());
            return true;
        }
    }

    /** 登出删会话键（黑名单保留防登出后 token 残留复用；sid 比对在黑名单之后）。 */
    public void clearSession(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.delete(SESSION_PREFIX + userId);
        } catch (Exception e) {
            log.warn("会话删除失败(已吞) userId={} : {}", userId, e.getMessage());
        }
    }
}
