// agent-platform/backend/src/main/java/com/superprogrammer/common/security/BanService.java
package com.superprogrammer.common.security;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.superprogrammer.auth.entity.User;
import com.superprogrammer.auth.mapper.UserMapper;
import com.superprogrammer.auth.security.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 封号即时生效服务（11x 加固 · P1-C3）：状态变更瞬间踢下线。
 *
 * <p><b>双保险模型</b>：
 * <ol>
 *   <li>删 {@code session:user:{uid}} —— 单点登录开启时，下一请求 sid 比对失败 → 401 SESSION_KICKED；</li>
 *   <li>设 {@code ban:user:{uid}} 标记（TTL=access 有效期）—— 单点登录开关关闭时 isCurrent 恒 true，
 *       删会话无效，由 ban 标记兜底；access token 自然过期后标记自动消失（refresh 已被 DB status 阻断）。</li>
 * </ol>
 *
 * <p>降级原则（与 SessionService 一致，可用性 > 强制力）：Redis 故障 → 吞异常 + WARN，
 * 在途 access ≤15min 内仍可用；DB status 仍阻新登录与 refresh（AuthService 状态检查），不放大故障。</p>
 */
@Slf4j
@Service
public class BanService {

    /** ban 标记键前缀（独立于单点登录会话键）。 */
    public static final String BAN_PREFIX = "ban:user:";
    /** 单点登录会话键前缀（与 SessionService.SESSION_PREFIX 同值，跨包不复用其包私有常量）。 */
    private static final String SESSION_PREFIX = "session:user:";

    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    public BanService(StringRedisTemplate redisTemplate, JwtUtil jwtUtil, UserMapper userMapper) {
        this.redisTemplate = redisTemplate;
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
    }

    /**
     * 封号/禁用/锁定时调：删会话 + 设 ban 标记。
     *
     * @param userId 目标用户
     * @param status 新状态（BANNED/DISABLED/LOCKED），仅记日志用
     */
    public void revoke(Long userId, String status) {
        try {
            redisTemplate.delete(SESSION_PREFIX + userId);
            // ban 标记 TTL=access 有效期：access 自然过期后标记失去意义（refresh 走 DB status 阻断）
            redisTemplate.opsForValue().set(BAN_PREFIX + userId, status,
                    jwtUtil.getAccessExpiration(), TimeUnit.MILLISECONDS);
            log.warn("账号状态吊销(即时踢下线) userId={} status={}", userId, status);
        } catch (Exception e) {
            log.warn("封号标记写入失败(降级,DB status 仍阻新登录/refresh) userId={} status={} : {}",
                    userId, status, e.getMessage());
        }
    }

    /** 解封/启用时调：删 ban 标记（会话已删，用户须重新登录）。 */
    public void restore(Long userId) {
        try {
            redisTemplate.delete(BAN_PREFIX + userId);
            log.warn("账号状态恢复 userId={}", userId);
        } catch (Exception e) {
            log.warn("解封标记删除失败(已吞,标记 TTL 到期自愈) userId={} : {}", userId, e.getMessage());
        }
    }

    /**
     * 自动锁号（11x 加固 · P3-C10 AutoResponder 用）：DB status=LOCKED + locked_until=now+minutes，
     * ban_reason 记触发规则码，再 revoke 即时踢下线。到期由 AccountUnlockScheduler 自动恢复 ACTIVE。
     *
     * <p>仅当当前状态为 ACTIVE 才落（守卫条件在 UPDATE WHERE 上：已被人工 BANNED/DISABLED 的不覆盖，
     * 避免自动处置降级人工处置）。DB 故障吞异常——锁号失败不阻监控主链（事件已落库待人工）。</p>
     *
     * @param userId    目标用户
     * @param minutes   锁定时长（分钟）
     * @param eventType 触发规则码（写 ban_reason 供审计追溯）
     */
    public void lockAccount(Long userId, int minutes, String eventType) {
        try {
            UpdateWrapper<User> uw = new UpdateWrapper<>();
            uw.eq("id", userId).eq("status", "ACTIVE").eq("deleted", 0)
                    .set("status", "LOCKED")
                    .set("locked_until", OffsetDateTime.now().plusMinutes(minutes))
                    .set("ban_reason", eventType);
            int rows = userMapper.update(null, uw);
            if (rows > 0) {
                revoke(userId, "LOCKED");
                log.warn("账号自动锁定 userId={} minutes={} reason={}", userId, minutes, eventType);
            } else {
                log.warn("账号自动锁定跳过(非ACTIVE或已删) userId={} reason={}", userId, eventType);
            }
        } catch (Exception e) {
            log.error("账号自动锁定失败(已吞,事件已落库待人工处置) userId={} reason={} : {}",
                    userId, eventType, e.toString());
        }
    }

    /**
     * ban 标记是否存在（JwtAuthenticationFilter 每请求校验）。
     * Redis 故障 → false（放行；DB status 仍阻新登录/refresh，在途 access 自然过期）。
     */
    public boolean isBanned(Long userId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BAN_PREFIX + userId));
        } catch (Exception e) {
            log.warn("ban 标记查询失败(降级放行) userId={} : {}", userId, e.getMessage());
            return false;
        }
    }
}
