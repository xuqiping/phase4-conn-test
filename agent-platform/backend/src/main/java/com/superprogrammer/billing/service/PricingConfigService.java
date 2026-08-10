package com.superprogrammer.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.dto.AvailablePricingModelVO;
import com.superprogrammer.billing.dto.PricingRuleRequest;
import com.superprogrammer.billing.dto.PricingRuleVO;
import com.superprogrammer.billing.dto.RatioTierRequest;
import com.superprogrammer.billing.dto.RatioTierVO;
import com.superprogrammer.billing.entity.PricingRuleEntity;
import com.superprogrammer.billing.entity.PointsRatioTierEntity;
import com.superprogrammer.billing.mapper.PricingRuleMapper;
import com.superprogrammer.billing.mapper.PointsRatioTierMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.mapper.LlmProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * admin 价表/比例配置服务：CRUD + 校验。
 * <p>价表校验：kind/mode 枚举合法 + 金额≥0（VIDEO TOKEN 需 priceInput，SECOND 需 pricePerSecond，IMAGE 需 pricePerImage）。
 * <p>比例校验（安全清单「阶梯区间不重叠不漏」）：跨当前生效集断言连续——
 * 首档 min=0、相邻 max=下档 min、末档 max=∞（null），无重叠无空隙。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingConfigService {

    private static final Set<String> KINDS = Set.of(
            PricingRuleEntity.KIND_CHAT, PricingRuleEntity.KIND_EMBED,
            PricingRuleEntity.KIND_IMAGE, PricingRuleEntity.KIND_VIDEO);
    private static final Set<String> VIDEO_MODES = Set.of(
            PricingRuleEntity.VIDEO_MODE_TOKEN, PricingRuleEntity.VIDEO_MODE_SECOND);

    private final PricingRuleMapper pricingRuleMapper;
    private final PointsRatioTierMapper tierMapper;
    private final LlmProviderMapper llmProviderMapper;
    private final ObjectMapper objectMapper;

    // ---------------- 价表 ----------------

    public List<PricingRuleVO> listPricingRules() {
        return pricingRuleMapper.selectList(new LambdaQueryWrapper<PricingRuleEntity>()
                        .orderByAsc(PricingRuleEntity::getKind)
                        .orderByAsc(PricingRuleEntity::getModel))
                .stream().map(PricingConfigService::toVO).toList();
    }

    public List<AvailablePricingModelVO> availablePricingModels() {
        Set<String> configured = new HashSet<>();
        for (PricingRuleEntity rule : pricingRuleMapper.selectList(new LambdaQueryWrapper<>())) {
            if (rule.getProviderId() != null && rule.getModel() != null) {
                configured.add(pricingIdentity(rule.getProviderId(), rule.getModel()));
            }
        }
        return llmProviderMapper.selectList(new LambdaQueryWrapper<LlmProviderEntity>()
                        .eq(LlmProviderEntity::getStatus, "ACTIVE"))
                .stream()
                .filter(provider -> "ACTIVE".equals(provider.getStatus()))
                .flatMap(provider -> toAvailableModels(provider))
                .filter(candidate -> !configured.contains(
                        pricingIdentity(candidate.getProviderId(), candidate.getModel())))
                .sorted(Comparator.comparing(AvailablePricingModelVO::getProviderName)
                        .thenComparing(AvailablePricingModelVO::getModel))
                .toList();
    }

    private Stream<AvailablePricingModelVO> toAvailableModels(LlmProviderEntity provider) {
        String kind = toPricingKind(provider.getCategory());
        if (kind == null) {
            log.warn("价表候选跳过未知供应商类别: providerId={} category={}",
                    provider.getId(), provider.getCategory());
            return Stream.empty();
        }
        return parseProviderModels(provider).stream()
                .map(model -> AvailablePricingModelVO.builder()
                        .providerId(provider.getId())
                        .providerName(provider.getDisplayName() != null
                                ? provider.getDisplayName() : provider.getName())
                        .model(model)
                        .kind(kind)
                        .build());
    }

    private String pricingIdentity(Long providerId, String model) {
        return providerId + "\u0000" + model.trim();
    }

    private List<String> parseProviderModels(LlmProviderEntity provider) {
        if (provider.getModels() == null || provider.getModels().isBlank()) {
            return List.of();
        }
        try {
            List<String> models = objectMapper.readValue(
                    provider.getModels(), new TypeReference<List<String>>() { });
            if (models == null) {
                return List.of();
            }
            return models.stream()
                    .filter(model -> model != null && !model.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
        } catch (Exception ex) {
            log.warn("价表候选跳过无法解析的供应商模型: providerId={}", provider.getId());
            return List.of();
        }
    }

    private String toPricingKind(String category) {
        if (category == null) {
            return null;
        }
        return switch (category) {
            case "CHAT" -> PricingRuleEntity.KIND_CHAT;
            case "EMBEDDING" -> PricingRuleEntity.KIND_EMBED;
            case "IMAGE" -> PricingRuleEntity.KIND_IMAGE;
            case "VIDEO" -> PricingRuleEntity.KIND_VIDEO;
            default -> null;
        };
    }

    @Transactional(rollbackFor = Exception.class)
    public PricingRuleVO createPricingRule(PricingRuleRequest req) {
        validatePricingRule(req);
        if (req.getProviderId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "providerId 不能为空");
        }
        LlmProviderEntity provider = llmProviderMapper.selectByIdForUpdate(req.getProviderId());
        if (provider == null || !"ACTIVE".equals(provider.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "全局供应商不存在或未启用");
        }
        if (!parseProviderModels(provider).contains(req.getModel().trim())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模型不属于所选全局供应商");
        }
        String expectedKind = toPricingKind(provider.getCategory());
        if (expectedKind == null || !expectedKind.equals(req.getKind())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "kind 与全局供应商类别不匹配");
        }
        long duplicateCount = pricingRuleMapper.selectCount(
                new LambdaQueryWrapper<PricingRuleEntity>()
                        .eq(PricingRuleEntity::getProviderId, req.getProviderId())
                        .eq(PricingRuleEntity::getModel, req.getModel().trim()));
        if (duplicateCount > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "该全局模型已配置价表");
        }
        PricingRuleEntity e = new PricingRuleEntity();
        applyRequest(req, e);
        pricingRuleMapper.insert(e);
        log.info("建价表 kind={} model={} providerId={}", e.getKind(), e.getModel(), e.getProviderId());
        return toVO(e);
    }

    @Transactional(rollbackFor = Exception.class)
    public PricingRuleVO updatePricingRule(Long id, PricingRuleRequest req) {
        validatePricingRule(req);
        PricingRuleEntity e = pricingRuleMapper.selectById(id);
        if (e == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "价表不存在 id=" + id);
        }
        if (!Objects.equals(e.getProviderId(), req.getProviderId())
                || !Objects.equals(e.getModel(), req.getModel().trim())
                || !Objects.equals(e.getKind(), req.getKind())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "编辑时不可修改 provider/model/kind");
        }
        applyRequest(req, e);
        pricingRuleMapper.updateById(e);
        return toVO(e);
    }

    private void validatePricingRule(PricingRuleRequest req) {
        if (req.getKind() == null || !KINDS.contains(req.getKind())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "kind 须为 CHAT/EMBED/IMAGE/VIDEO");
        }
        if (req.getModel() == null || req.getModel().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空");
        }
        nonNegative(req.getPriceInputPerMillion(), "priceInputPerMillion");
        nonNegative(req.getPriceOutputPerMillion(), "priceOutputPerMillion");
        nonNegative(req.getPricePerSecond(), "pricePerSecond");
        nonNegative(req.getPricePerImage(), "pricePerImage");
        if (PricingRuleEntity.KIND_VIDEO.equals(req.getKind())) {
            if (req.getVideoBillingMode() == null || !VIDEO_MODES.contains(req.getVideoBillingMode())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "VIDEO 须指定 videoBillingMode: TOKEN|SECOND");
            }
            if (PricingRuleEntity.VIDEO_MODE_SECOND.equals(req.getVideoBillingMode())
                    && (req.getPricePerSecond() == null || req.getPricePerSecond().signum() < 0)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "SECOND 模式须配 pricePerSecond");
            }
        }
        if (PricingRuleEntity.KIND_IMAGE.equals(req.getKind())
                && (req.getPricePerImage() == null || req.getPricePerImage().signum() < 0)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "IMAGE 须配 pricePerImage");
        }
    }

    private void applyRequest(PricingRuleRequest req, PricingRuleEntity e) {
        e.setKind(req.getKind());
        e.setProviderId(req.getProviderId());
        e.setModel(req.getModel().trim());
        e.setPriceInputPerMillion(req.getPriceInputPerMillion());
        e.setPriceOutputPerMillion(req.getPriceOutputPerMillion());
        e.setVideoBillingMode(req.getVideoBillingMode());
        e.setPricePerSecond(req.getPricePerSecond());
        e.setPricePerImage(req.getPricePerImage());
        e.setEffectiveFrom(req.getEffectiveFrom() != null ? req.getEffectiveFrom() : OffsetDateTime.now());
    }

    private static PricingRuleVO toVO(PricingRuleEntity e) {
        return PricingRuleVO.builder()
                .id(e.getId()).kind(e.getKind()).providerId(e.getProviderId()).model(e.getModel())
                .priceInputPerMillion(e.getPriceInputPerMillion())
                .priceOutputPerMillion(e.getPriceOutputPerMillion())
                .videoBillingMode(e.getVideoBillingMode())
                .pricePerSecond(e.getPricePerSecond())
                .pricePerImage(e.getPricePerImage())
                .effectiveFrom(e.getEffectiveFrom())
                .build();
    }

    // ---------------- 阶梯比例 ----------------

    public List<RatioTierVO> listRatioTiers() {
        return tierMapper.selectList(new LambdaQueryWrapper<PointsRatioTierEntity>()
                        .orderByAsc(PointsRatioTierEntity::getMinAmount))
                .stream().map(PricingConfigService::toVO).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public RatioTierVO createRatioTier(RatioTierRequest req) {
        PointsRatioTierEntity e = new PointsRatioTierEntity();
        applyTier(req, e);
        tierMapper.insert(e);
        validateTierContinuity(); // 入库后再校验整集，不合法回滚
        log.info("建阶梯比例 min={} max={} ratio={}", e.getMinAmount(), e.getMaxAmount(), e.getRatio());
        return toVO(e);
    }

    @Transactional(rollbackFor = Exception.class)
    public RatioTierVO updateRatioTier(Long id, RatioTierRequest req) {
        PointsRatioTierEntity e = tierMapper.selectById(id);
        if (e == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "阶梯比例不存在 id=" + id);
        }
        applyTier(req, e);
        tierMapper.updateById(e);
        validateTierContinuity();
        return toVO(e);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteRatioTier(Long id) {
        if (tierMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "阶梯比例不存在 id=" + id);
        }
        tierMapper.deleteById(id);
        validateTierContinuity();
    }

    private void applyTier(RatioTierRequest req, PointsRatioTierEntity e) {
        if (req.getMinAmount() == null || req.getMinAmount().signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "minAmount 须≥0");
        }
        if (req.getMaxAmount() != null && req.getMaxAmount().signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "maxAmount 须为正或留空(=∞)");
        }
        if (req.getMaxAmount() != null && req.getMaxAmount().compareTo(req.getMinAmount()) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "maxAmount 须 > minAmount");
        }
        if (req.getRatio() == null || req.getRatio().signum() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "ratio 须>0");
        }
        e.setMinAmount(req.getMinAmount());
        e.setMaxAmount(req.getMaxAmount());
        e.setRatio(req.getRatio());
        e.setEffectiveFrom(req.getEffectiveFrom() != null ? req.getEffectiveFrom() : OffsetDateTime.now());
    }

    /**
     * 校验当前生效阶梯集连续：首档 min=0、相邻 max=下档 min、末档 max=null(∞)、无重叠。
     * 不合法抛 BAD_REQUEST，配合 @Transactional 回滚本次增删改。
     */
    private void validateTierContinuity() {
        List<PointsRatioTierEntity> tiers = tierMapper.selectList(null);
        if (tiers == null || tiers.isEmpty()) {
            return; // 空集允许（首条入库时）
        }
        // 拷贝后再排序：selectList 返回的列表不保证可变（mock/某些 driver 返回不可变集合）
        List<PointsRatioTierEntity> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparing(PointsRatioTierEntity::getMinAmount));
        if (sorted.get(0).getMinAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "阶梯首档 minAmount 必须从 0 开始");
        }
        for (int i = 0; i < sorted.size(); i++) {
            PointsRatioTierEntity cur = sorted.get(i);
            if (cur.getMaxAmount() == null) {
                if (i != sorted.size() - 1) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "仅末档可 maxAmount=∞(留空)");
                }
                break; // 末档 ∞，后续无
            }
            if (i + 1 < sorted.size()) {
                BigDecimal nextMin = sorted.get(i + 1).getMinAmount();
                if (cur.getMaxAmount().compareTo(nextMin) != 0) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                            "阶梯不连续/重叠：档" + cur.getMinAmount() + " 的 max(" + cur.getMaxAmount()
                                    + ") 须等于下档 min(" + nextMin + ")");
                }
            } else {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "末档 maxAmount 须留空(=∞)");
            }
        }
    }

    private static RatioTierVO toVO(PointsRatioTierEntity e) {
        return RatioTierVO.builder()
                .id(e.getId()).minAmount(e.getMinAmount()).maxAmount(e.getMaxAmount())
                .ratio(e.getRatio()).effectiveFrom(e.getEffectiveFrom())
                .build();
    }

    private void nonNegative(BigDecimal v, String name) {
        if (v != null && v.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " 须≥0");
        }
    }
}
