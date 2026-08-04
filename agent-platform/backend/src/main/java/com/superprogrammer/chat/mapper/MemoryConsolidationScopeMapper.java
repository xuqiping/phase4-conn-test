package com.superprogrammer.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.chat.entity.MemoryConsolidationScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 自动总结 scope 勾选 + worker 任务锁 mapper（V47 + V51 计划12 · E）。
 * <p>
 * <b>双节点互斥</b>（设计 §3.4 + plan E「FOR UPDATE SKIP LOCKED」）：worker 定时认领走
 * {@link #claimAutoScopes}（SKIP LOCKED）；手动触发走 {@link #acquireManualLock}（条件置锁）。
 * 锁生命周期三段（同 IndexJobTxService 范式）：claim/acquire（短事务置锁）→ process（事务外 LLM）→
 * {@link #releaseLockSuccess}/{@link #releaseLockFailure}（短事务清锁）。
 * <p>
 * <b>幂等</b>：last_run_at >= 周期起点则跳过（防重复压缩 LLM 计费）。
 */
@Mapper
public interface MemoryConsolidationScopeMapper extends BaseMapper<MemoryConsolidationScope> {

    /** E-6 worker 定时认领：auto_enabled=true AND (locked_until IS NULL OR &lt; now)
     *  AND (last_run_at IS NULL OR &lt; periodStart)，LIMIT n FOR UPDATE SKIP LOCKED。
     *  <p>调用方须在 @Transactional 内调，紧接 markClaimed 置锁（同 tx），否则行锁随事务结束释放。 */
    List<MemoryConsolidationScope> claimAutoScopes(@Param("limit") int limit,
                                                   @Param("now") OffsetDateTime now,
                                                   @Param("periodStart") OffsetDateTime periodStart);

    /** E-6 认领后置锁（locked_until = now + lockMinutes）。 */
    int markClaimed(@Param("id") Long id,
                    @Param("lockedUntil") OffsetDateTime lockedUntil);

    /** E-7 手动触发条件置锁：仅当未锁或锁过期时置锁（CAS 式，affected=1 表示抢到）。
     *  调用方须先 upsertScope 保证行存在（PERSONAL 由 V47 trigger 默认建，PROJECT 按 upsert）。 */
    int acquireManualLock(@Param("id") Long id,
                          @Param("now") OffsetDateTime now,
                          @Param("lockedUntil") OffsetDateTime lockedUntil);

    /** E-6/E-7 成功释放：清 locked_until + 置 last_run_at=now（幂等锚点）。 */
    int releaseLockSuccess(@Param("id") Long id, @Param("now") OffsetDateTime now);

    /** E-6/E-7 失败释放：仅清 locked_until（不更 last_run_at，允许下轮重试）。 */
    int releaseLockFailure(@Param("id") Long id);

    /** E-7 upsert（ON CONFLICT 更新 auto_enabled；PROJECT 行按需建，PERSONAL 已由 trigger）。 */
    int upsertScope(@Param("userId") Long userId,
                    @Param("scopeKind") String scopeKind,
                    @Param("projectId") Long projectId,
                    @Param("autoEnabled") boolean autoEnabled,
                    @Param("now") OffsetDateTime now);

    /** E-7 取（user, scope_kind, project_id）行（PROJECT 行可能未建 → null）。 */
    MemoryConsolidationScope findByUserAndScope(@Param("userId") Long userId,
                                                @Param("scopeKind") String scopeKind,
                                                @Param("projectId") Long projectId);
}
