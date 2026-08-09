package com.superprogrammer.billing.service;

import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

/**
 * 低余额并行闸门（安全体系 S2 · L7，SEC-FR-126）：余额低于阈值的用户禁止多任务并行，
 * 只放行 {@code billing.low-balance.max-inflight}（默认 1）个在途任务，其余抛 42902
 * 「余额不足，请等待当前任务完成」。
 *
 * <p>模型：Redis {@code inflight:u:{userId}} INCR/DECR 计数，TTL 30min 兜底自动释放
 * （进程崩溃/流中断未 DECR → 最多误闸 30min）；正常路径 try/finally release。
 *
 * <p>挂点只挂<b>用户主动入口</b>：LlmGateway chat/chatStream + MediaGenTaskService.submit
 * （worker 完成/失败/超时回调 release）；embed 及 userId=null 系统任务不过闸。
 *
 * <p>降级原则（与 S1 一致，可用性 > 强制力）：Redis 故障 → 放行 + WARN，不杀主链。
 * <p>阈值/上限挂 system_settings 实时查库（{@link SystemSettingService}），管理员页面改即生效；
 * 非法值回退默认（threshold=100，maxInflight=1）。
 *
 * <p>已知近似（可接受，方向 fail-open）：submit 时计费关/Redis 故障未计数，worker release
 * 会 DECR 到负数 → 兜底清零删键，最差仅计数偏少不误闸。
 */
@Slf4j
@Service
public class InflightGateService {

    /** 在途计数键前缀。 */
    static final String INFLIGHT_PREFIX = "inflight:u:";
    /** 槽位泄漏兜底：键 TTL（进程崩溃未 DECR → 自动释放）。 */
    static final long KEY_TTL_MINUTES = 30;

    /** 默认阈值（积分）：余额低于此值触发并行限制。 */
    static final long DEFAULT_THRESHOLD = 100;
    /** 默认低余额最大在途数。 */
    static final long DEFAULT_MAX_INFLIGHT = 1;

    private final StringRedisTemplate redisTemplate;
    private final PointsWalletService walletService;
    private final SystemSettingService systemSettingService;
    private final AuditLogService auditLogService;

    public InflightGateService(StringRedisTemplate redisTemplate,
                               PointsWalletService walletService,
                               SystemSettingService systemSettingService,
                               AuditLogService auditLogService) {
        this.redisTemplate = redisTemplate;
        this.walletService = walletService;
        this.systemSettingService = systemSettingService;
        this.auditLogService = auditLogService;
    }

    /**
     * 用户入口过闸：低余额且在途已达上限 → 审计 + 抛 {@link ErrorCode#LOW_BALANCE_INFLIGHT_LIMIT}。
     *
     * @return true=本调用持有槽位（调用方须 finally {@link #release}）；false=未持有
     *         （系统调用/计费关/余额充足/余额≤0 交给 requireAffordable 拦/Redis 故障降级），无需 release
     */
    public boolean acquire(Long userId) {
        if (userId == null || !walletService.isEnabled()) {
            return false;
        }
        try {
            // 余额≤0 不过闸：紧随的 requireAffordable 会抛 INSUFFICIENT_POINTS，语义不重复
            BigDecimal balance = walletService.getBalance(userId);
            if (balance == null || balance.signum() <= 0) {
                return false;
            }
            String key = INFLIGHT_PREFIX + userId;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 仅新建设 TTL：每次 acquire 刷新会削弱泄漏兜底；release 到 0 删键是主路径
                redisTemplate.expire(key, KEY_TTL_MINUTES, TimeUnit.MINUTES);
            }
            long threshold = systemSettingService.getLong(
                    SystemSettingService.BILLING_LOW_BALANCE_THRESHOLD, DEFAULT_THRESHOLD);
            long maxInflight = systemSettingService.getLong(
                    SystemSettingService.BILLING_LOW_BALANCE_MAX_INFLIGHT, DEFAULT_MAX_INFLIGHT);
            if (balance.compareTo(BigDecimal.valueOf(threshold)) < 0 && count != null && count > maxInflight) {
                // 超上限：退回本次计数 + 安全审计 + 固定话术拒绝
                decrementFloor(key);
                auditRejected(userId, balance, count - 1);
                throw new BusinessException(ErrorCode.LOW_BALANCE_INFLIGHT_LIMIT);
            }
            return true;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("在途闸门故障(降级放行) userId={} : {}", userId, e.getMessage());
            return false;
        }
    }

    /** 释放槽位（DECR，到 0 删键；异常吞 + WARN——绝不阻断主链收尾）。 */
    public void release(Long userId) {
        if (userId == null || !walletService.isEnabled()) {
            return;
        }
        try {
            decrementFloor(INFLIGHT_PREFIX + userId);
        } catch (Exception e) {
            log.warn("在途计数释放失败(已吞,30min TTL兜底) userId={} : {}", userId, e.getMessage());
        }
    }

    /** DECR 兜底清零：计数绝不落负（submit 未计数而 worker 释放的错配场景 fail-open）。 */
    private void decrementFloor(String key) {
        Long count = redisTemplate.opsForValue().decrement(key);
        if (count != null && count <= 0L) {
            redisTemplate.delete(key);
        }
    }

    /** 拦截留痕：billing/inflight_rejected（异步，吞异常不阻断拒绝本身）。 */
    private void auditRejected(Long userId, BigDecimal balance, long inflight) {
        try {
            auditLogService.record(auditLogService.fromMdc("billing", "inflight_rejected", "user",
                    String.valueOf(userId),
                    "{\"balance\":" + balance.toPlainString() + ",\"inflight\":" + inflight + "}",
                    AuditLogEntity.RESULT_FAIL));
        } catch (Exception e) {
            log.warn("在途闸门审计落库失败(已吞) userId={} : {}", userId, e.getMessage());
        }
    }
}
