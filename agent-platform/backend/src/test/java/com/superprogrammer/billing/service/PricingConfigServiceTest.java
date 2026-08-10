package com.superprogrammer.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.dto.PricingRuleRequest;
import com.superprogrammer.billing.dto.RatioTierRequest;
import com.superprogrammer.billing.entity.PricingRuleEntity;
import com.superprogrammer.billing.entity.PointsRatioTierEntity;
import com.superprogrammer.billing.mapper.PricingRuleMapper;
import com.superprogrammer.billing.mapper.PointsRatioTierMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.mapper.LlmProviderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
    @Mock
    private LlmProviderMapper llmProviderMapper;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

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
        PricingRuleRequest req = pricingReq(1L, "gpt-4", PricingRuleEntity.KIND_CHAT);
        req.setPriceOutputPerMillion(new BigDecimal("2"));
        when(llmProviderMapper.selectByIdForUpdate(1L))
                .thenReturn(provider(1L, "聊天", "CHAT", "gpt-4"));
        assertThatCode(() -> service.createPricingRule(req)).doesNotThrowAnyException();
    }

    @Test
    void createPricingRule_missingProviderId_throws() {
        // AC-F20-01：新增价表必须绑定候选中的全局供应商。
        assertThatThrownBy(() -> service.createPricingRule(
                pricingReq(null, "chat-model", PricingRuleEntity.KIND_CHAT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("providerId");
    }

    @Test
    void createPricingRule_inactiveProvider_throws() {
        // AC-F20-01：创建必须锁定并复核 ACTIVE 全局供应商，不能信任前端候选。
        LlmProviderEntity inactive = provider(9L, "停用供应商", "CHAT", "chat-model");
        inactive.setStatus("INACTIVE");
        when(llmProviderMapper.selectByIdForUpdate(9L)).thenReturn(inactive);

        assertThatThrownBy(() -> service.createPricingRule(
                pricingReq(9L, "chat-model", PricingRuleEntity.KIND_CHAT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    void createPricingRule_modelNotOwnedByProvider_throws() {
        // AC-F20-01：请求模型必须真实存在于锁定供应商的 models 中。
        when(llmProviderMapper.selectByIdForUpdate(1L))
                .thenReturn(provider(1L, "聊天", "CHAT", "owned-model"));

        assertThatThrownBy(() -> service.createPricingRule(
                pricingReq(1L, "forged-model", PricingRuleEntity.KIND_CHAT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于");
    }

    @Test
    void createPricingRule_kindDoesNotMatchProviderCategory_throws() {
        // AC-F20-01：kind 由供应商类别决定，不能由调用方伪造。
        when(llmProviderMapper.selectByIdForUpdate(2L))
                .thenReturn(provider(2L, "向量", "EMBEDDING", "embed-model"));

        assertThatThrownBy(() -> service.createPricingRule(
                pricingReq(2L, "embed-model", PricingRuleEntity.KIND_CHAT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("kind");
    }

    @Test
    void createPricingRule_duplicateProviderModel_throws() {
        // AC-F20-01：供应商行锁内复查重复，防止并发穿透候选列表。
        when(llmProviderMapper.selectByIdForUpdate(1L))
                .thenReturn(provider(1L, "聊天", "CHAT", "chat-model"));
        when(pricingRuleMapper.countConflictingProviderModel(1L, "chat-model")).thenReturn(1L);

        assertThatThrownBy(() -> service.createPricingRule(
                pricingReq(1L, "chat-model", PricingRuleEntity.KIND_CHAT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已配置");
    }

    @Test
    void updatePricingRule_identityChange_throws() {
        // AC-F20-01：编辑只能改价格/模式/生效时间，三元身份不可变。
        PricingRuleEntity existing = new PricingRuleEntity();
        existing.setId(6L);
        existing.setProviderId(1L);
        existing.setModel("chat-model");
        existing.setKind(PricingRuleEntity.KIND_CHAT);
        when(pricingRuleMapper.selectById(6L)).thenReturn(existing);

        assertThatThrownBy(() -> service.updatePricingRule(
                6L, pricingReq(2L, "other-model", PricingRuleEntity.KIND_EMBED)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可修改");
    }

    @Test
    void availablePricingModels_mapsAllActiveProviderCategories() {
        // AC-F20-01：全局供应商类别必须集中映射到计费 kind。
        when(llmProviderMapper.selectList(any())).thenReturn(List.of(
                provider(1L, "聊天", "CHAT", "chat-model"),
                provider(2L, "向量", "EMBEDDING", "embed-model"),
                provider(3L, "图片", "IMAGE", "image-model"),
                provider(4L, "视频", "VIDEO", "video-model")));
        var result = service.availablePricingModels();

        org.assertj.core.api.Assertions.assertThat(result)
                .extracting("providerId", "model", "kind")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1L, "chat-model", "CHAT"),
                        org.assertj.core.groups.Tuple.tuple(2L, "embed-model", "EMBED"),
                        org.assertj.core.groups.Tuple.tuple(3L, "image-model", "IMAGE"),
                        org.assertj.core.groups.Tuple.tuple(4L, "video-model", "VIDEO"));
    }

    @Test
    void availablePricingModels_excludesAlreadyConfiguredProviderModel() {
        // AC-F20-01：候选是全局模型与既有 (providerId, model) 的差集。
        when(llmProviderMapper.selectList(any())).thenReturn(List.of(
                provider(1L, "聊天", "CHAT", "chat-model")));
        PricingRuleEntity configured = new PricingRuleEntity();
        configured.setProviderId(1L);
        configured.setModel("chat-model");
        configured.setKind(PricingRuleEntity.KIND_CHAT);
        when(pricingRuleMapper.selectList(any())).thenReturn(List.of(configured));

        org.assertj.core.api.Assertions.assertThat(service.availablePricingModels()).isEmpty();
    }

    @Test
    void availablePricingModels_excludesModelOccupiedByGlobalRule() {
        // V66 的 provider_id=null 是全局价：同名模型不能再作为任何 provider 的“未配置候选”。
        when(llmProviderMapper.selectList(any())).thenReturn(List.of(
                provider(1L, "聊天A", "CHAT", "chat-model"),
                provider(2L, "聊天B", "CHAT", "chat-model")));
        PricingRuleEntity global = new PricingRuleEntity();
        global.setProviderId(null);
        global.setModel("chat-model");
        global.setKind(PricingRuleEntity.KIND_CHAT);
        when(pricingRuleMapper.selectList(any())).thenReturn(List.of(global));

        org.assertj.core.api.Assertions.assertThat(service.availablePricingModels()).isEmpty();
    }

    @Test
    void createPricingRule_duplicateCheckIncludesGlobalRule() {
        when(llmProviderMapper.selectByIdForUpdate(1L))
                .thenReturn(provider(1L, "聊天", "CHAT", "chat-model"));
        when(pricingRuleMapper.countConflictingProviderModel(1L, "chat-model")).thenReturn(1L);

        assertThatThrownBy(() -> service.createPricingRule(
                pricingReq(1L, "chat-model", PricingRuleEntity.KIND_CHAT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已配置");
    }

    @Test
    void availablePricingModels_normalizesModelsAndSkipsUnusableProviders() {
        // AC-F20-01：模型 trim+去重；停用、未知类别、坏 JSON 均不进入候选。
        LlmProviderEntity active = provider(1L, "聊天", "CHAT", "unused");
        active.setModels("[\" chat-model \",\"chat-model\",\" \",\"chat-model\"]");
        LlmProviderEntity inactive = provider(2L, "停用", "CHAT", "disabled-model");
        inactive.setStatus("INACTIVE");
        LlmProviderEntity badJson = provider(3L, "坏配置", "CHAT", "unused");
        badJson.setModels("not-json");
        LlmProviderEntity unknown = provider(4L, "未知", "AUDIO", "audio-model");
        when(llmProviderMapper.selectList(any())).thenReturn(List.of(active, inactive, badJson, unknown));
        when(pricingRuleMapper.selectList(any())).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThat(service.availablePricingModels())
                .extracting("providerId", "model", "kind")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, "chat-model", "CHAT"));
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

    private LlmProviderEntity provider(Long id, String name, String category, String model) {
        LlmProviderEntity p = new LlmProviderEntity();
        p.setId(id);
        p.setDisplayName(name);
        p.setName(name);
        p.setCategory(category);
        p.setStatus("ACTIVE");
        p.setModels("[\"" + model + "\"]");
        return p;
    }

    private PricingRuleRequest pricingReq(Long providerId, String model, String kind) {
        PricingRuleRequest req = new PricingRuleRequest();
        req.setProviderId(providerId);
        req.setModel(model);
        req.setKind(kind);
        req.setPriceInputPerMillion(BigDecimal.ONE);
        req.setPriceOutputPerMillion(BigDecimal.ONE);
        return req;
    }
}
