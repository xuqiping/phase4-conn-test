package com.superprogrammer.media.service.internal;

import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.media.entity.MediaGenTask;
import com.superprogrammer.media.mapper.MediaGenTaskMapper;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 每用户媒体生成任务并发闸门（2x 第三轮问题修复 C3，15x_并发.md 三问落地）：
 * video / image 两类**独立计数**，在途任务超过 {@code system_settings} 上限的提交直接拒
 * {@link ErrorCode#MEDIA_CONCURRENT_LIMIT}（42904）。
 *
 * <p>模型与 {@code billing.InflightGateService}（低余额闸门）同范式：Redis
 * {@code inflight:media:{kind}:u:{userId}} INCR 原子预占，TTL 30min 兜底自愈
 * （进程崩溃/终态未 release → 最多误闸 30min）；上限挂 system_settings 实时查库
 * （管理员改即生效，D5 拍板：出厂默认 video=2、image=3，0=不限制）。
 *
 * <p>配对释放：submit 侧 acquire 成功后任务落库前异常 → 调用方 catch 内 release；
 * 正常路径由 {@code MediaGenTaskWorker} 终态 release（**仅在终态迁移真正落库时**——
 * mark* 方法 UPDATE 带 {@code status IN (PENDING,RUNNING)} 条件，影响行=1 才放），
 * 防锁过期重认领双 worker 重复 DECR 造成少计 → 超卖。
 *
 * <p>降级原则（与 RateLimiter/低余额闸门一致，可用性 &gt; 强制力）：
 * Redis 故障 / settings 读取失败 → 放行 + WARN，不杀主链。
 *
 * <p>对账（运维兜底）：每小时比 Redis 计数 vs DB 未终态任务数（V123 部分索引），
 * 漂移 &gt; 2 → WARN（供日志采集，接告警面板后续再说）。
 */
@Slf4j
@Service
public class MediaInflightGateService {

    public static final String KIND_VIDEO = "video";
    public static final String KIND_IMAGE = "image";

    /** 在途计数键前缀（完整键：inflight:media:{kind}:u:{userId}）。 */
    static final String KEY_PREFIX = "inflight:media:";
    /** 槽位泄漏兜底：键 TTL（终态未 release → 自动释放）。 */
    static final long KEY_TTL_MINUTES = 30;
    /** 出厂默认：每用户同时生视频 2 个（D5 拍板）。 */
    static final long DEFAULT_VIDEO_LIMIT = 2;
    /** 出厂默认：每用户同时生图 3 个（D5 拍板）。 */
    static final long DEFAULT_IMAGE_LIMIT = 3;
    /** 对账漂移告警阈值（差超过此值 WARN）。 */
    static final long RECONCILE_DRIFT_WARN = 2;

    private final StringRedisTemplate redisTemplate;
    private final SystemSettingService systemSettingService;
    private final MediaGenTaskMapper taskMapper;

    public MediaInflightGateService(StringRedisTemplate redisTemplate,
                                    SystemSettingService systemSettingService,
                                    MediaGenTaskMapper taskMapper) {
        this.redisTemplate = redisTemplate;
        this.systemSettingService = systemSettingService;
        this.taskMapper = taskMapper;
    }

    /**
     * 提交入口过闸：该类在途任务已达上限 → 抛 {@link ErrorCode#MEDIA_CONCURRENT_LIMIT}（计数已回退）。
     *
     * @return true=本调用持有槽位（调用方须在落库失败的 catch 里 {@link #release} 配对释放）；
     *         false=未持有（系统调用 userId=null / 上限 0 不限制 / 降级放行），无需 release
     */
    public boolean acquire(Long userId, String kind) {
        if (userId == null) {
            return false;
        }
        // 上限先读（DB）：读失败 → 降级放行且不动计数（避免 INCR 后无人 release 的泄漏窗口）
        final long limit;
        try {
            limit = limitOf(kind);
        } catch (Exception e) {
            log.warn("媒体并发上限读取失败(降级放行) userId={} kind={} : {}", userId, kind, e.getMessage());
            return false;
        }
        if (limit <= 0) {
            return false; // 0/负 = 不限制（管理员显式关闭）
        }
        String key = key(userId, kind);
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 仅新建设 TTL：每次 acquire 刷新会削弱泄漏兜底；release 到 0 删键是主路径
                redisTemplate.expire(key, KEY_TTL_MINUTES, TimeUnit.MINUTES);
            }
            if (count != null && count > limit) {
                // 超上限：退回本次计数 + WARN（拒 42904，固定话术不透传内部细节）
                decrementFloor(key);
                log.warn("媒体并发上限拦截 userId={} kind={} count={} limit={}", userId, kind, count - 1, limit);
                throw new BusinessException(ErrorCode.MEDIA_CONCURRENT_LIMIT);
            }
            return true;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("媒体并发闸门故障(降级放行) userId={} kind={} : {}", userId, kind, e.getMessage());
            return false;
        }
    }

    /** 释放槽位（DECR，到 0 删键；异常吞 + WARN——绝不阻断 worker 收尾）。 */
    public void release(Long userId, String kind) {
        if (userId == null) {
            return;
        }
        try {
            decrementFloor(key(userId, kind));
        } catch (Exception e) {
            log.warn("媒体并发计数释放失败(已吞,30min TTL兜底) userId={} kind={} : {}", userId, kind, e.getMessage());
        }
    }

    /** DECR 兜底清零：计数绝不落负（acquire 降级未计数而 worker 释放的错配场景 fail-open）。 */
    private void decrementFloor(String key) {
        Long count = redisTemplate.opsForValue().decrement(key);
        if (count != null && count <= 0L) {
            redisTemplate.delete(key);
        }
    }

    private static String key(Long userId, String kind) {
        return KEY_PREFIX + kind + ":u:" + userId;
    }

    /** 上限读 system_settings（管理员改即生效）；未知 kind 防御性抛 IllegalArgument → 上层降级放行。 */
    private long limitOf(String kind) {
        return switch (kind == null ? "" : kind) {
            case KIND_VIDEO -> systemSettingService.getLong(
                    SystemSettingService.MEDIA_CONCURRENT_VIDEO, DEFAULT_VIDEO_LIMIT);
            case KIND_IMAGE -> systemSettingService.getLong(
                    SystemSettingService.MEDIA_CONCURRENT_IMAGE, DEFAULT_IMAGE_LIMIT);
            default -> throw new IllegalArgumentException("unknown kind: " + kind);
        };
    }

    /**
     * 每小时对账（运维兜底）：Redis 在途计数 vs DB 未终态任务数（V123 部分索引）。
     * 漂移 &gt; {@link #RECONCILE_DRIFT_WARN} → WARN（Redis 少计=超卖风险 / 多计=泄漏，均靠 TTL 或人工处理）。
     * 全程吞异常——对账失败不影响任何主链。
     */
    @Scheduled(fixedDelayString = "${media.inflight-reconcile-ms:3600000}")
    public void reconcile() {
        try {
            List<Map<String, Object>> rows = taskMapper.countActiveByUserAndType();
            for (Map<String, Object> row : rows) {
                Long userId = asLong(row.get("userId"));
                String taskType = String.valueOf(row.get("taskType"));
                long dbCount = asLong(row.get("cnt"));
                if (userId == null) {
                    continue;
                }
                String kind = kindOfTaskType(taskType);
                if (kind == null) {
                    continue;
                }
                String key = key(userId, kind);
                String raw = redisTemplate.opsForValue().get(key);
                long redisCount = raw == null ? 0L : Long.parseLong(raw);
                long drift = Math.abs(redisCount - dbCount);
                if (drift > RECONCILE_DRIFT_WARN) {
                    log.warn("媒体并发计数漂移 userId={} kind={} redis={} db={} (差 {} > {})"
                                    + "——少计=超卖风险查重复release；多计=泄漏等TTL或手动DEL {}",
                            userId, kind, redisCount, dbCount, drift, RECONCILE_DRIFT_WARN, key);
                }
            }
        } catch (Exception e) {
            log.warn("媒体并发对账失败(已吞) : {}", e.getMessage());
        }
    }

    /** task_type → 闸门 kind（TEXT2IMAGE/IMAGE2IMAGE=image；TEXT2VIDEO/IMAGE2VIDEO=video）。 */
    static String kindOfTaskType(String taskType) {
        if (MediaGenTask.TYPE_TEXT2IMAGE.equals(taskType) || MediaGenTask.TYPE_IMAGE2IMAGE.equals(taskType)) {
            return KIND_IMAGE;
        }
        if (MediaGenTask.TYPE_TEXT2VIDEO.equals(taskType) || MediaGenTask.TYPE_IMAGE2VIDEO.equals(taskType)) {
            return KIND_VIDEO;
        }
        return null;
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
