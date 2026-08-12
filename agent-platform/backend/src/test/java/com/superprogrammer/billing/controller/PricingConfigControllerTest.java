package com.superprogrammer.billing.controller;

import com.superprogrammer.billing.dto.AvailablePricingModelVO;
import com.superprogrammer.billing.service.PricingConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingConfigControllerTest {

    @Mock
    private PricingConfigService pricingConfigService;

    @InjectMocks
    private PricingConfigController controller;

    @Test
    void availablePricingModels_returnsServiceCandidates() {
        // AC-F20-01：管理端候选接口返回服务层生成的最小候选列表。
        var candidate = AvailablePricingModelVO.builder()
                .providerId(7L).providerName("供应商").model("model-x").kind("CHAT").build();
        when(pricingConfigService.availablePricingModels()).thenReturn(List.of(candidate));

        var response = controller.availablePricingModels();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).containsExactly(candidate);
    }
}
