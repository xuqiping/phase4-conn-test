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
        when(pricingRuleMapper.countConflictingProviderModelHasRefResolution(1L, "chat-model", false, null)).thenReturn(1L);

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

        // 7x-1（V152）：VIDEO 模型展开为 (参考面×分辨率槽位) 2×5=10 条候选；非 VIDEO 仍一模型一条
        org.assertj.core.api.Assertions.assertThat(result)
                .filteredOn(c -> !"VIDEO".equals(c.getKind()))
                .extracting("providerId", "model", "kind")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1L, "chat-model", "CHAT"),
                        org.assertj.core.groups.Tuple.tuple(2L, "embed-model", "EMBED"),
                        org.assertj.core.groups.Tuple.tuple(3L, "image-model", "IMAGE"));
        org.assertj.core.api.Assertions.assertThat(result)
                .filteredOn(c -> "VIDEO".equals(c.getKind()))
                .hasSize(10);
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
        when(pricingRuleMapper.countConflictingProviderModelHasRefResolution(1L, "chat-model", false, null)).thenReturn(1L);

        assertThatThrownBy(() -> service.createPricingRule(
                pricingReq(1L, "chat-model", PricingRuleEntity.KIND_CHAT)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已配置");
    }

    // ---------------- 7x-1：VIDEO 只配一面参考维度时候选保留 ----------------

    @Test
    void availablePricingModels_videoConfiguredFalseOnly_stillCandidateWithHint() {
        // 7x-1：VIDEO 配了「无参考」行后，候选必须保留（admin 还要能配「有参考」行），且带维度提示。
        when(llmProviderMapper.selectList(any())).thenReturn(List.of(
                provider(4L, "视频", "VIDEO", "seedance")));
        PricingRuleEntity configured = new PricingRuleEntity();
        configured.setProviderId(4L);
        configured.setModel("seedance");
        configured.setKind(PricingRuleEntity.KIND_VIDEO);
        configured.setHasReference(false);
        when(pricingRuleMapper.selectList(any())).thenReturn(List.of(configured));

        var result = service.availablePricingModels();
        // 7x-1（V152）：(false,通用) 已配 → 占 1 槽位，余 9 候选；「有参考·通用」必须在列（带身份提示）
        org.assertj.core.api.Assertions.assertThat(result).hasSize(9);
        org.assertj.core.api.Assertions.assertThat(result)
                .filteredOn(c -> Boolean.TRUE.equals(c.getHasReference()) && c.getResolution() == null)
                .hasSize(1);
        org.assertj.core.api.Assertions.assertThat(result)
                .filteredOn(c -> !Boolean.TRUE.equals(c.getHasReference()) && c.getResolution() == null)
                .isEmpty();
        org.assertj.core.api.Assertions.assertThat(result)
                .allMatch(c -> c.getHint() != null && c.getHint().contains("价行"));
    }

    @Test
    void availablePricingModels_videoBothDimsConfigured_excluded() {
        // 7x-1：两面都配齐后候选才消失（与非 VIDEO 行为对齐）。
        when(llmProviderMapper.selectList(any())).thenReturn(List.of(
                provider(4L, "视频", "VIDEO", "seedance")));
        PricingRuleEntity noRef = new PricingRuleEntity();
        noRef.setProviderId(4L);
        noRef.setModel("seedance");
        noRef.setKind(PricingRuleEntity.KIND_VIDEO);
        noRef.setHasReference(false);
        PricingRuleEntity withRef = new PricingRuleEntity();
        withRef.setProviderId(4L);
        withRef.setModel("seedance");
        withRef.setKind(PricingRuleEntity.KIND_VIDEO);
        withRef.setHasReference(true);
        when(pricingRuleMapper.selectList(any())).thenReturn(List.of(noRef, withRef));

        // 7x-1（V152）：两面通用行配齐后，分辨率槽位候选仍在（4 槽×2 面=8）；全槽配齐才消失
        org.assertj.core.api.Assertions.assertThat(service.availablePricingModels()).hasSize(8);
    }

    @Test
    void availablePricingModels_videoAllSlotsConfigured_excluded() {
        // 7x-1（V152）：(2 参考面 × 5 槽位) 全配齐 → 候选消失
        when(llmProviderMapper.selectList(any())).thenReturn(List.of(
                provider(4L, "视频", "VIDEO", "seedance")));
        java.util.List<PricingRuleEntity> rules = new java.util.ArrayList<>();
        for (boolean side : new boolean[]{false, true}) {
            for (String res : new String[]{null, "480p", "720p", "1080p", "4k"}) {
                PricingRuleEntity e = new PricingRuleEntity();
                e.setProviderId(4L);
                e.setModel("seedance");
                e.setKind(PricingRuleEntity.KIND_VIDEO);
                e.setHasReference(side);
                e.setResolution(res);
                rules.add(e);
            }
        }
        when(pricingRuleMapper.selectList(any())).thenReturn(rules);

        org.assertj.core.api.Assertions.assertThat(service.availablePricingModels()).isEmpty();
    }

    @Test
    void deletePricingRule_notFound_throws() {
        when(pricingRuleMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.deletePricingRule(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void deletePricingRule_existing_deletes() {
        PricingRuleEntity e = new PricingRuleEntity();
        e.setId(13L);
        e.setKind(PricingRuleEntity.KIND_VIDEO);
        e.setProviderId(6L);
        e.setModel("Cdance2.0");
        when(pricingRuleMapper.selectById(13L)).thenReturn(e);
        assertThatCode(() -> service.deletePricingRule(13L)).doesNotThrowAnyException();
        org.mockito.Mockito.verify(pricingRuleMapper).deleteById(13L);
    }

    // ---------------- 7x-3：has_reference 视频参考定价 ----------------

    @Test
    void createPricingRule_videoHasReferenceTwoVariants_ok() {
        // 7x-3：同一 VIDEO 模型可配 false 和 true 两行（不冲突），admin 分别定价。
        when(llmProviderMapper.selectByIdForUpdate(4L))
                .thenReturn(provider(4L, "视频", "VIDEO", "seedance"));
        when(pricingRuleMapper.countConflictingProviderModelHasRefResolution(4L, "seedance", true, null)).thenReturn(0L);

        PricingRuleRequest req = pricingReq(4L, "seedance", PricingRuleEntity.KIND_VIDEO);
        req.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_TOKEN);
        req.setPriceInputPerMillion(new BigDecimal("10"));
        req.setHasReference(true);
        assertThatCode(() -> service.createPricingRule(req)).doesNotThrowAnyException();
    }

    @Test
    void createPricingRule_nonVideoWithHasReferenceTrue_throws() {
        // 7x-3：非 VIDEO kind 不允许 hasReference=true（仅对视频参考有意义）。
        PricingRuleRequest req = pricingReq(1L, "chat-model", PricingRuleEntity.KIND_CHAT);
        req.setHasReference(true);
        assertThatThrownBy(() -> service.createPricingRule(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hasReference");
    }

    @Test
    void updatePricingRule_changeHasReference_throws() {
        // 7x-3：has_reference 视为身份一部分，编辑不可改（请新增另一变体行）。
        PricingRuleEntity existing = new PricingRuleEntity();
        existing.setId(8L);
        existing.setProviderId(4L);
        existing.setModel("seedance");
        existing.setKind(PricingRuleEntity.KIND_VIDEO);
        existing.setHasReference(false);
        when(pricingRuleMapper.selectById(8L)).thenReturn(existing);

        PricingRuleRequest req = pricingReq(4L, "seedance", PricingRuleEntity.KIND_VIDEO);
        req.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_TOKEN);
        req.setHasReference(true); // 改了 has_reference
        assertThatThrownBy(() -> service.updatePricingRule(8L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hasReference");
    }

    // ---------------- 7x-2：导出 / 模板 / 导入 ----------------

    @Test
    void exportAll_mapsAllFields() {
        PricingRuleEntity e = new PricingRuleEntity();
        e.setKind(PricingRuleEntity.KIND_VIDEO);
        e.setProviderId(4L);
        e.setModel("seedance");
        e.setHasReference(true);
        e.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_TOKEN);
        e.setPriceInputPerMillion(new BigDecimal("10"));
        when(pricingRuleMapper.selectList(any())).thenReturn(List.of(e));

        var items = service.exportAll();
        org.assertj.core.api.Assertions.assertThat(items).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(items.get(0).getHasReference()).isTrue();
        org.assertj.core.api.Assertions.assertThat(items.get(0).getKind()).isEqualTo(PricingRuleEntity.KIND_VIDEO);
        org.assertj.core.api.Assertions.assertThat(items.get(0).getPriceInputPerMillion())
                .isEqualByComparingTo("10");
    }

    @Test
    void generateTemplate_excludesConfiguredModels() {
        // 模板只含未配置模型（复用 availablePricingModels 过滤）
        when(llmProviderMapper.selectList(any())).thenReturn(List.of(
                provider(1L, "聊天", "CHAT", "chat-model"),
                provider(3L, "图片", "IMAGE", "image-model")));
        PricingRuleEntity configured = new PricingRuleEntity();
        configured.setProviderId(1L);
        configured.setModel("chat-model");
        configured.setKind(PricingRuleEntity.KIND_CHAT);
        when(pricingRuleMapper.selectList(any())).thenReturn(List.of(configured));

        var template = service.generateTemplate();
        // chat-model 已配置 → 模板只剩 image-model
        org.assertj.core.api.Assertions.assertThat(template)
                .extracting("model")
                .containsExactly("image-model");
    }

    @Test
    void importAll_overLimit_throws() {
        // 超 200 行抛 BAD_REQUEST
        java.util.List<com.superprogrammer.billing.dto.PricingRuleExportItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) {
            com.superprogrammer.billing.dto.PricingRuleExportItem it = new com.superprogrammer.billing.dto.PricingRuleExportItem();
            it.setModel("m" + i);
            items.add(it);
        }
        assertThatThrownBy(() -> service.importAll(items))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("200");
    }

    @Test
    void importAll_invalidProvider_skippedToFailed() {
        // provider 不存在 → incFailed，不中断整体（无其他行时 created=0/failed=1）
        com.superprogrammer.billing.dto.PricingRuleExportItem item = new com.superprogrammer.billing.dto.PricingRuleExportItem();
        item.setKind(PricingRuleEntity.KIND_CHAT);
        item.setProviderId(999L);
        item.setModel("ghost");
        item.setPriceInputPerMillion(new BigDecimal("1"));
        when(llmProviderMapper.selectById(999L)).thenReturn(null);

        var result = service.importAll(List.of(item));
        org.assertj.core.api.Assertions.assertThat(result.getFailed()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(result.getCreated()).isZero();
        org.assertj.core.api.Assertions.assertThat(result.getErrors()).hasSize(1);
    }

    @Test
    void importAll_newRow_inserts() {
        com.superprogrammer.billing.dto.PricingRuleExportItem item = new com.superprogrammer.billing.dto.PricingRuleExportItem();
        item.setKind(PricingRuleEntity.KIND_CHAT);
        item.setProviderId(1L);
        item.setModel("chat-model");
        item.setPriceInputPerMillion(new BigDecimal("2"));
        item.setPriceOutputPerMillion(new BigDecimal("3"));
        when(llmProviderMapper.selectById(1L)).thenReturn(provider(1L, "聊天", "CHAT", "chat-model"));
        when(pricingRuleMapper.countConflictingProviderModelHasRefResolution(1L, "chat-model", false, null)).thenReturn(0L);

        var result = service.importAll(List.of(item));
        org.assertj.core.api.Assertions.assertThat(result.getCreated()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(result.getUpdated()).isZero();
    }

    @Test
    void importAll_existingRow_upsertsPrice() {
        // 已配置 → 覆盖价格（updated++）
        com.superprogrammer.billing.dto.PricingRuleExportItem item = new com.superprogrammer.billing.dto.PricingRuleExportItem();
        item.setKind(PricingRuleEntity.KIND_CHAT);
        item.setProviderId(1L);
        item.setModel("chat-model");
        item.setPriceInputPerMillion(new BigDecimal("5"));
        when(llmProviderMapper.selectById(1L)).thenReturn(provider(1L, "聊天", "CHAT", "chat-model"));
        when(pricingRuleMapper.countConflictingProviderModelHasRefResolution(1L, "chat-model", false, null)).thenReturn(1L);
        PricingRuleEntity existing = new PricingRuleEntity();
        existing.setId(10L);
        existing.setKind(PricingRuleEntity.KIND_CHAT);
        existing.setProviderId(1L);
        existing.setModel("chat-model");
        when(pricingRuleMapper.findEffectiveWithResolution("CHAT", 1L, "chat-model", false, null)).thenReturn(existing);

        var result = service.importAll(List.of(item));
        org.assertj.core.api.Assertions.assertThat(result.getUpdated()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(result.getCreated()).isZero();
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

    // ---------------- 7x-1/7x-2（V152）：resolution / estYuanPerSecond 校验 ----------------

    @Test
    void createPricingRule_videoSecondBadResolution_throws() {
        // SECOND 分辨率行：非支持集（480p/720p/1080p/4k）→ 400
        PricingRuleRequest req = pricingReq(4L, "seedance", PricingRuleEntity.KIND_VIDEO);
        req.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        req.setPricePerSecond(new BigDecimal("0.10"));
        req.setResolution("8k");
        assertThatThrownBy(() -> service.createPricingRule(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("resolution");
    }

    @Test
    void createPricingRule_videoTokenWithResolution_throws() {
        // TOKEN 模式不按分辨率计价 → resolution 必须留空
        PricingRuleRequest req = pricingReq(4L, "seedance", PricingRuleEntity.KIND_VIDEO);
        req.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_TOKEN);
        req.setResolution("720p");
        assertThatThrownBy(() -> service.createPricingRule(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("resolution");
    }

    @Test
    void createPricingRule_secondWithEstYuan_throws() {
        // 预估秒价仅 TOKEN 用（SECOND 估价直接走秒价）→ SECOND 配了即 400
        PricingRuleRequest req = pricingReq(4L, "seedance", PricingRuleEntity.KIND_VIDEO);
        req.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        req.setPricePerSecond(new BigDecimal("0.10"));
        req.setEstYuanPerSecond(new BigDecimal("0.20"));
        assertThatThrownBy(() -> service.createPricingRule(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("estYuanPerSecond");
    }

    @Test
    void createPricingRule_secondResolutionNormalizedToLowercase() {
        // 大写 4K 归一化为 4k（与任务侧 resolution 归一化对齐，防 IS NOT DISTINCT FROM 失配）
        when(llmProviderMapper.selectByIdForUpdate(4L))
                .thenReturn(provider(4L, "视频", "VIDEO", "seedance"));
        PricingRuleRequest req = pricingReq(4L, "seedance", PricingRuleEntity.KIND_VIDEO);
        req.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        req.setPricePerSecond(new BigDecimal("0.10"));
        req.setResolution("4K");

        service.createPricingRule(req);

        org.mockito.ArgumentCaptor<PricingRuleEntity> captor =
                org.mockito.ArgumentCaptor.forClass(PricingRuleEntity.class);
        org.mockito.Mockito.verify(pricingRuleMapper).insert(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getResolution()).isEqualTo("4k");
    }

    @Test
    void createPricingRule_sameResolutionDuplicate_throws() {
        // 同 (provider, model, hasRef, resolution) 已配 → 409；不同分辨率行不冲突（dup=0 不拦）
        when(llmProviderMapper.selectByIdForUpdate(4L))
                .thenReturn(provider(4L, "视频", "VIDEO", "seedance"));
        when(pricingRuleMapper.countConflictingProviderModelHasRefResolution(
                4L, "seedance", false, "720p")).thenReturn(1L);
        PricingRuleRequest req = pricingReq(4L, "seedance", PricingRuleEntity.KIND_VIDEO);
        req.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        req.setPricePerSecond(new BigDecimal("0.10"));
        req.setResolution("720p");

        assertThatThrownBy(() -> service.createPricingRule(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已配置");
    }

    @Test
    void createPricingRule_tokenWithEstYuan_ok() {
        // TOKEN + 预估秒价：合法，落库保留 estYuanPerSecond、resolution=null
        when(llmProviderMapper.selectByIdForUpdate(4L))
                .thenReturn(provider(4L, "视频", "VIDEO", "seedance"));
        PricingRuleRequest req = pricingReq(4L, "seedance", PricingRuleEntity.KIND_VIDEO);
        req.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_TOKEN);
        req.setEstYuanPerSecond(new BigDecimal("0.20"));

        service.createPricingRule(req);

        org.mockito.ArgumentCaptor<PricingRuleEntity> captor =
                org.mockito.ArgumentCaptor.forClass(PricingRuleEntity.class);
        org.mockito.Mockito.verify(pricingRuleMapper).insert(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getEstYuanPerSecond())
                .isEqualByComparingTo(new BigDecimal("0.20"));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getResolution()).isNull();
    }

    @Test
    void updatePricingRule_resolutionChange_throws() {
        // resolution 是身份字段：编辑不可改（通用行 → 720p 行视同改身份）
        PricingRuleEntity existing = new PricingRuleEntity();
        existing.setId(6L);
        existing.setProviderId(4L);
        existing.setModel("seedance");
        existing.setKind(PricingRuleEntity.KIND_VIDEO);
        existing.setHasReference(false);
        existing.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        existing.setResolution(null);
        when(pricingRuleMapper.selectById(6L)).thenReturn(existing);

        PricingRuleRequest req = pricingReq(4L, "seedance", PricingRuleEntity.KIND_VIDEO);
        req.setVideoBillingMode(PricingRuleEntity.VIDEO_MODE_SECOND);
        req.setPricePerSecond(new BigDecimal("0.10"));
        req.setResolution("720p");

        assertThatThrownBy(() -> service.updatePricingRule(6L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不可修改");
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
