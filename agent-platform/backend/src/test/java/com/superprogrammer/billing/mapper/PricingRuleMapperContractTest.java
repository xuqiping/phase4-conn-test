package com.superprogrammer.billing.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class PricingRuleMapperContractTest {

    @Test
    void duplicateSqlTreatsGlobalRuleAsConflict() throws Exception {
        Method method = PricingRuleMapper.class.getMethod(
                "countConflictingProviderModel", Long.class, String.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertThat(sql)
                .contains("model = #{model}")
                .contains("provider_id = #{providerid}")
                .contains("provider_id is null");
    }
}
