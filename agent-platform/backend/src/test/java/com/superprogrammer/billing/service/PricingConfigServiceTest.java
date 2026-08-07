package com.superprogrammer.billing.service;

import com.superprogrammer.billing.dto.PricingRuleRequest;
import com.superprogrammer.billing.dto.RatioTierRequest;
import com.superprogrammer.billing.entity.PricingRuleEntity;
import com.superprogrammer.billing.entity.PointsRatioTierEntity;
import com.superprogrammer.billing.mapper.PricingRuleMapper;
import com.superprogrammer.billing.mapper.PointsRatioTierMapper;
import com.superprogrammer.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * PricingConfigService 单测：聚焦阶梯连续性校验（不重叠不漏）+ 价表 kind/mode 校验。
 */
@ExtendWith(MockitoExtension.class)
class PricingConfigServiceTest {

    @Mock
    private PricingRuleMapper pricingRuleMapper;
    @Mock
    private PointsRatioTierMapper tierMapper;

    @InjectMocks
    private PricingConfigService service;

    // ---------------- 阶梯连续性 ----------------

    @Test
    void createRatioTier_singleFromZero_valid() {
        when(tierMapper.selectList(any())).thenReturn(List.of(tier(BigDecimal.ZERO, null, "100")));
        RatioTierRequest req = tierReq("0", null, "100");
        assertThatCode(() -> service.createRatioTier(req)).doesNotThrowAnyException();
    }

    @Test
    void createRatioTier_nonZeroStart_throws() {
        when(tierMapper.selectList(any())).thenReturn(List.of(tier(new BigDecimal("5"), null, "100")));
        assertThatThrownBy(() -> service.createRatioTier(tierReq("5", null, "100")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("从 0 开始");
    }

    @Test
    void createRatioTier_gap_throws() {
        // [0,5) 与 [10,∞) 间有空隙（max5≠下档min10）
        when(tierMapper.selectList(any())).thenReturn(List.of(
                tier(BigDecimal.ZERO, new BigDecimal("5"), "100"),
                tier(new BigDecimal("10"), null, "90")));
        assertThatThrownBy(() -> service.createRatioTier(tierReq("10", null, "90")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不连续");
    }

    @Test
    void createRatioTier_lastTierMaxNotNull_throws() {
        when(tierMapper.selectList(any())).thenReturn(List.of(
                tier(BigDecimal.ZERO, new BigDecimal("5"), "100"))); // 唯一档却 max=5（非末档∞）
        assertThatThrownBy(() -> service.createRatioTier(tierReq("0", "5", "100")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("末档");
    }

    @Test
    void createRatioTier_maxLteMin_throws() {
        // applyTier 自身校验：max≤min
        assertThatThrownBy(() -> service.createRatioTier(tierReq("10", "5", "100")))
                .isInstanceOf(BusinessException.class);
    }

    // ---------------- 价表 ----------------

    @Test
    void createPricingRule_videoWithoutMode_throws() {
        PricingRuleRequest req = new PricingRuleRequest();
        req.setKind(PricingRuleEntity.KIND_VIDEO);
        req.setModel("seedance");
        req.setPriceInputPerMillion(new BigDecimal("3"));
        assertThatThrownBy(() -> service.createPricingRule(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TOKEN|SECOND");
    }

    @Test
    void createPricingRule_badKind_throws() {
        PricingRuleRequest req = new PricingRuleRequest();
        req.setKind("AUDIO");
        req.setModel("x");
        assertThatThrownBy(() -> service.createPricingRule(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("kind");
    }

    @Test
    void createPricingRule_chatValid_ok() {
        PricingRuleRequest req = new PricingRuleRequest();
        req.setKind(PricingRuleEntity.KIND_CHAT);
        req.setModel("gpt-4");
        req.setPriceInputPerMillion(new BigDecimal("1"));
        req.setPriceOutputPerMillion(new BigDecimal("2"));
        assertThatCode(() -> service.createPricingRule(req)).doesNotThrowAnyException();
    }

    // helpers

    private PointsRatioTierEntity tier(BigDecimal min, BigDecimal max, String ratio) {
        PointsRatioTierEntity t = new PointsRatioTierEntity();
        t.setMinAmount(min);
        t.setMaxAmount(max);
        t.setRatio(new BigDecimal(ratio));
        return t;
    }

    private RatioTierRequest tierReq(String min, String max, String ratio) {
        RatioTierRequest r = new RatioTierRequest();
        r.setMinAmount(new BigDecimal(min));
        r.setMaxAmount(max != null ? new BigDecimal(max) : null);
        r.setRatio(new BigDecimal(ratio));
        return r;
    }
}
