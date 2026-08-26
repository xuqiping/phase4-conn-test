package com.superprogrammer.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.superprogrammer.billing.entity.PricingRuleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 模型价表 Mapper。
 * <p>{@link #findEffective} 询价：provider 专属价优先于全局价，取 effective_from &lt;= now 最新。
 */
@Mapper
public interface PricingRuleMapper extends BaseMapper<PricingRuleEntity> {

    /**
     * 新建 provider 专属价表时，同时把历史全局价（provider_id IS NULL）视为同名占用。
     * 这与“未配置模型”候选列表的兼容语义保持一致，但不改变询价时专属价优先、全局价回落的规则。
     */
    @Select("""
            SELECT COUNT(*) FROM pricing_rule
            WHERE model = #{model}
              AND (provider_id = #{providerId} OR provider_id IS NULL)
            """)
    long countConflictingProviderModel(@Param("providerId") Long providerId,
                                       @Param("model") String model);

    /**
     * 7x-3：判重带 has_reference 维度。VIDEO 同模型可合法配两行（false+true 不冲突）；
     * 其他 kind 一律按 false 查（无重复）。历史全局价（provider_id IS NULL）仍视为占用。
     * <p>D6（V160）：行身份去 resolution 维（SECOND 分辨率行已合并为通用行），
     * 本方法即唯一判重口径；带 resolution 的旧 SQL 已随维度一并移除。
     */
    @Select("""
            SELECT COUNT(*) FROM pricing_rule
            WHERE model = #{model}
              AND (provider_id = #{providerId} OR provider_id IS NULL)
              AND has_reference = #{hasReference}
            """)
    long countConflictingProviderModelHasRef(@Param("providerId") Long providerId,
                                             @Param("model") String model,
                                             @Param("hasReference") boolean hasReference);

    /**
     * 询价取生效价表（含 has_reference 精确匹配）。
     * <p>命中策略：(kind+model+has_reference+effective&lt;=now) AND (provider_id=给定 OR 全局)，
     * <code>ORDER BY (provider_id IS NULL) ASC</code> 让 provider 专属价（非空）排前，
     * 再按 effective_from DESC 取最新。providerId 传 null 时只命中全局价。
     * <p>fallback 到 false 行（无参考/兜底）由 {@link
     * com.superprogrammer.billing.service.PricingService#computeCost} 编排，本 SQL 只做精确匹配。
     */
    @Select("SELECT * FROM pricing_rule "
            + "WHERE kind = #{kind} AND model = #{model} AND effective_from <= NOW() "
            + "AND (provider_id = #{providerId} OR provider_id IS NULL) "
            + "AND has_reference = #{hasReference} "
            + "ORDER BY (provider_id IS NULL) ASC, effective_from DESC "
            + "LIMIT 1")
    PricingRuleEntity findEffective(@Param("kind") String kind,
                                    @Param("providerId") Long providerId,
                                    @Param("model") String model,
                                    @Param("hasReference") boolean hasReference);

    /**
     * 7x-1（V152）：+resolution 精确匹配（VIDEO SECOND 分辨率行；其他场景传 null 即等价旧行为，
     * 因非 SECOND 行 resolution 恒 NULL）。
     * <p>fallback 链（精确分辨率→通用 NULL 行→has_reference=false 行）由
     * {@link com.superprogrammer.billing.service.PricingService#computeCost} 编排，本 SQL 只做精确匹配。
     */
    @Select("SELECT * FROM pricing_rule "
            + "WHERE kind = #{kind} AND model = #{model} AND effective_from <= NOW() "
            + "AND (provider_id = #{providerId} OR provider_id IS NULL) "
            + "AND has_reference = #{hasReference} "
            + "AND resolution IS NOT DISTINCT FROM #{resolution} "
            + "ORDER BY (provider_id IS NULL) ASC, effective_from DESC "
            + "LIMIT 1")
    PricingRuleEntity findEffectiveWithResolution(@Param("kind") String kind,
                                                  @Param("providerId") Long providerId,
                                                  @Param("model") String model,
                                                  @Param("hasReference") boolean hasReference,
                                                  @Param("resolution") String resolution);

    /**
     * 兼容旧调用方（无 has_reference）：恒按 false 查。
     * @deprecated 使用 {@link #findEffective(String, Long, String, boolean)}。
     */
    @Deprecated
    default PricingRuleEntity findEffective(String kind, Long providerId, String model) {
        return findEffective(kind, providerId, model, false);
    }

    /**
     * D9（V160）：TOKEN est 预估秒价近 7 天偏差聚合（SQL AVG/SUM 不拉明细）。
     * <p>口径：近 7 天 SUCCEEDED 视频任务（estimated_cost&gt;0），实耗取 llm_usage_logs
     * 该任务 SUCCESS 行 points_consumed 之和（预扣结算/直接扣同源）；偏差 = Σ实耗/Σ预估−1，
     * 按 (provider_id, model, has_reference) 聚合；样本 &lt; 3 的组不出行。
     * <p>has_reference 由 task_type 派生：IMAGE2VIDEO=有参考，TEXT2VIDEO=无参考。
     */
    @Select("""
            WITH t AS (
                SELECT m.provider_id, m.model,
                       (m.task_type = 'IMAGE2VIDEO') AS has_ref,
                       m.estimated_cost AS est,
                       (SELECT COALESCE(SUM(u.points_consumed), 0) FROM llm_usage_logs u
                         WHERE u.task_id = m.id AND u.status = 'SUCCESS') AS actual
                FROM media_gen_tasks m
                WHERE m.task_type IN ('TEXT2VIDEO', 'IMAGE2VIDEO')
                  AND m.status = 'SUCCEEDED'
                  AND m.estimated_cost > 0
                  AND m.created_at >= NOW() - INTERVAL '7 days'
            )
            SELECT provider_id      AS "providerId",
                   model            AS "model",
                   has_ref          AS "hasReference",
                   ROUND((SUM(actual) / SUM(est) - 1) * 100) AS "deviationPct",
                   COUNT(*)         AS "sampleCount"
            FROM t
            WHERE actual > 0
            GROUP BY provider_id, model, has_ref
            HAVING COUNT(*) >= 3
            """)
    java.util.List<com.superprogrammer.billing.dto.EstDeviationVO> selectVideoEstDeviation7d();
}
