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
     * 兼容旧调用方（无 has_reference）：恒按 false 查。
     * @deprecated 使用 {@link #findEffective(String, Long, String, boolean)}。
     */
    @Deprecated
    default PricingRuleEntity findEffective(String kind, Long providerId, String model) {
        return findEffective(kind, providerId, model, false);
    }
}
