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
     * 询价取生效价表。
     * <p>命中策略：(kind+model+effective&lt;=now) AND (provider_id=给定 OR 全局)，
     * <code>ORDER BY (provider_id IS NULL) ASC</code> 让 provider 专属价（非空）排前，
     * 再按 effective_from DESC 取最新。providerId 传 null 时只命中全局价。
     * <p>providerId = NULL 的分支：<code>provider_id = #{providerId}</code> 在 SQL 里恒假
     * （NULL=NULL），自动落到 <code>provider_id IS NULL</code> 全局行。
     */
    @Select("SELECT * FROM pricing_rule "
            + "WHERE kind = #{kind} AND model = #{model} AND effective_from <= NOW() "
            + "AND (provider_id = #{providerId} OR provider_id IS NULL) "
            + "ORDER BY (provider_id IS NULL) ASC, effective_from DESC "
            + "LIMIT 1")
    PricingRuleEntity findEffective(@Param("kind") String kind,
                                    @Param("providerId") Long providerId,
                                    @Param("model") String model);
}
