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
            log.warn("会话写入失败(降级放行,单点登录本轮不生效;Redis 恢复后该用户须重新登录一次) userId={} : {}", userId, e.getMessage());
        }
        return sid;
    }

    /**
     * 校验 token 的 sid 是否当前会话。false=被踢/旧 token（调用方拒绝）。
     * 开关关闭、开关读取失败（DB 抖动）或 Redis 故障 → true（降级放行 + WARN，不杀主链）。
     */
    public boolean isCurrent(Long userId, String sid) {
        boolean enabled;
        try {
            enabled = isSingleSessionEnabled();
        } catch (Exception e) {
            // 开关读库异常（DB 抖动）→ 降级放行；与 Redis 故障同范式，可用性 > 强制力
            log.warn("单点登录开关读取失败(降级放行) userId={} : {}", userId, e.getMessage());
            return true;
        }
        if (!enabled) {
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

    /**
     * 强制踢掉该用户所有会话（修改密码 / 重置密码后调用）。
     *
     * <p>直接删 {@code session:user:{userId}} 键——与单点登录踢旧语义一致：键被删后，
     * 持有旧 sid 的 token 下次请求比对时 {@code current=null ≠ sid} → 拒绝（40104 固定话术），
     * 等效于「所有设备强制重登」。
     *
     * <p>与 {@link #clearSession} 的区别：clearSession 比对 sid 只删自己（防 logout-bomb）；
     * 本方法是无条件全删——仅用于用户主动改/重置密码这种「主动放弃所有会话」的强语义场景
     * （沉淀约束 4 论证）。
     *
     * <p>降级：Redis 故障 → WARN 不阻断业务（旧 token 残留至 access 自然过期，最长 15min）。
     */
    public void kickAllSessions(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.delete(SESSION_PREFIX + userId);
        } catch (Exception e) {
            log.warn("踢所有会话失败(已吞,旧 token 残留至自然过期) userId={} : {}", userId, e.getMessage());
        }
    }

    /**
     * 登出删会话键——**比对 sid 只删自己的会话**：旧（已被踢）会话登出时不得删掉新会话的键
     * （否则被踢者/15min 窗口内的 token 持有者可反复 logout 踢飞当前会话 = logout-bomb）。
     * GET-then-DEL 非原子，竞态良性：并发登录落在 GET 与 DEL 之间至多误删一次新键（该会话重登即愈）。
     * sid=null（旧 token 登出）→ 不删（旧 token 本就过不了过滤器，防御性兜底）。
     */
    public void clearSession(Long userId, String sid) {
        if (userId == null || sid == null) {
            return;
        }
        try {
            String key = SESSION_PREFIX + userId;
            String current = redisTemplate.opsForValue().get(key);
            if (sid.equals(current)) {
                redisTemplate.delete(key);
            }
        } catch (Exception e) {
            log.warn("会话删除失败(已吞) userId={} : {}", userId, e.getMessage());
        }
    }
}
