package com.superprogrammer.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.dto.AvailablePricingModelVO;
import com.superprogrammer.billing.dto.PricingRuleExportItem;
import com.superprogrammer.billing.dto.PricingImportResult;
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
            PricingRuleEntity.KIND_RERANK, PricingRuleEntity.KIND_IMAGE, PricingRuleEntity.KIND_VIDEO);
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

    // ---------------- 7x-2：价表导出 / 模板 / 导入 ----------------

    /**
     * 导出当前全量价表（7x-2）。价表无加密，纯字段拷贝。
     * <p>仅 admin 可调（Controller 层 @RequirePermission("pricing:manage")）。
     */
    public List<PricingRuleExportItem> exportAll() {
        return pricingRuleMapper.selectList(new LambdaQueryWrapper<PricingRuleEntity>()
                        .orderByAsc(PricingRuleEntity::getKind)
                        .orderByAsc(PricingRuleEntity::getModel))
                .stream().map(PricingConfigService::toExportItem).toList();
    }

    /**
     * 生成「填充模板」（7x-2）：联动全局供应商，自动把<b>未配置过</b>的模型预填为空白价表行，
     * 用户只填价格即可上传。天然区分 LLM/图片/视频（kind 字段即区分）。
     * <p>复用 {@link #availablePricingModels()}（已排除 provider 专属配置 + 历史全局价同名模型）。
     */
    public List<PricingRuleExportItem> generateTemplate() {
        return availablePricingModels().stream()
                .map(c -> {
                    PricingRuleExportItem item = new PricingRuleExportItem();
                    item.setKind(c.getKind());
                    item.setProviderId(c.getProviderId());
                    item.setProviderName(c.getProviderName());
                    item.setModel(c.getModel());
                    item.setHasReference(false);
                    // 价格字段全留 null（由用户填）
                    return item;
                })
                .sorted(Comparator.comparing(PricingRuleExportItem::getKind)
                        .thenComparing(i -> i.getProviderName() == null ? "" : i.getProviderName())
                        .thenComparing(i -> i.getModel() == null ? "" : i.getModel()))
                .toList();
    }

    /** 导入单批上限（防恶意巨大体导致 OOM/长事务），与供应商导入一致。 */
    private static final int IMPORT_MAX_SIZE = 200;

    /**
     * 批量导入价表（7x-2）：按 (providerId, model, kind, hasReference) upsert，非法行跳过记入 errors。
     * <p>规则：
     * <ul>
     *   <li>size > 200 → 抛 BAD_REQUEST；</li>
     *   <li>逐行校验：复用 {@link #validatePricingRule} + provider 存在 ACTIVE + model 属于 provider +
     *       kind 匹配 category；不过 → incFailed，不中断整体；</li>
     *   <li>upsert：命中 {@link PricingRuleMapper#countConflictingProviderModelHasRef} → UPDATE 价格 +
     *       effective_from=now（覆盖旧价）；未命中 → INSERT；</li>
     *   <li>{@code providerName} 字段导入时忽略（仅按 providerId 定位）；</li>
     *   <li>无内存缓存需 reload（{@code findEffective} 实时查库）。</li>
     * </ul>
     */
    public PricingImportResult importAll(List<PricingRuleExportItem> items) {
        if (items == null) {
            items = List.of();
        }
        if (items.size() > IMPORT_MAX_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "单次导入上限 " + IMPORT_MAX_SIZE + " 条，当前 " + items.size() + " 条");
        }
        PricingImportResult result = PricingImportResult.builder().build();
        for (int i = 0; i < items.size(); i++) {
            PricingRuleExportItem item = items.get(i);
            int lineNo = i + 1;
            try {
                upsertPricingRow(item, result);
            } catch (Exception e) {
                log.warn("导入价表第{}行失败 model={}: {}", lineNo, item.getModel(), e.getMessage());
                result.incFailed("第" + lineNo + "行 model=" + item.getModel() + " 失败: " + e.getMessage());
            }
        }
        return result;
    }

    /** 单行 upsert：校验 → 查重 → 命中更新 / 未命中新建。 */
    private void upsertPricingRow(PricingRuleExportItem item, PricingImportResult result) {
        PricingRuleRequest req = toItemRequest(item);
        validatePricingRule(req);
        if (req.getProviderId() == null) {
            result.incFailed("providerId 为空（价表导入仅支持 provider 专属价，不支持全局价）");
            return;
        }
        LlmProviderEntity provider = llmProviderMapper.selectById(req.getProviderId());
        if (provider == null || !"ACTIVE".equals(provider.getStatus())) {
            result.incFailed("providerId=" + req.getProviderId() + " 不存在或未启用");
            return;
        }
        if (!parseProviderModels(provider).contains(req.getModel().trim())) {
            result.incFailed("model=" + req.getModel() + " 不属于 providerId=" + req.getProviderId());
            return;
        }
        String expectedKind = toPricingKind(provider.getCategory());
        if (expectedKind == null || !expectedKind.equals(req.getKind())) {
            result.incFailed("kind=" + req.getKind() + " 与 provider 类别 " + provider.getCategory() + " 不匹配");
            return;
        }
        boolean hasRef = effectiveHasReference(req);
        long dup = pricingRuleMapper.countConflictingProviderModelHasRef(
                req.getProviderId(), req.getModel().trim(), hasRef);
        if (dup > 0) {
            // upsert：覆盖价格，刷新 effective_from=now（生效即按新价）
            PricingRuleEntity existing = pricingRuleMapper.findEffective(
                    req.getKind(), req.getProviderId(), req.getModel().trim(), hasRef);
            if (existing == null) {
                // 命中 count 但 findEffective 取不到（如 effective_from 未来态）：按新建
                PricingRuleEntity e = new PricingRuleEntity();
                applyRequest(req, e);
                pricingRuleMapper.insert(e);
                result.incCreated();
                return;
            }
            applyRequest(req, existing);
            existing.setEffectiveFrom(OffsetDateTime.now());
            pricingRuleMapper.updateById(existing);
            result.incUpdated();
        } else {
            PricingRuleEntity e = new PricingRuleEntity();
            applyRequest(req, e);
            pricingRuleMapper.insert(e);
            result.incCreated();
        }
    }

    /** 导入 DTO → Request（复用既有校验/apply 路径），hasReference null 归一为 false。 */
    private PricingRuleRequest toItemRequest(PricingRuleExportItem item) {
        PricingRuleRequest req = new PricingRuleRequest();
        req.setKind(item.getKind());
        req.setProviderId(item.getProviderId());
        req.setModel(item.getModel());
        req.setHasReference(item.getHasReference());
        req.setPriceInputPerMillion(item.getPriceInputPerMillion());
        req.setPriceOutputPerMillion(item.getPriceOutputPerMillion());
        req.setVideoBillingMode(item.getVideoBillingMode());
        req.setPricePerSecond(item.getPricePerSecond());
        req.setPricePerImage(item.getPricePerImage());
        return req;
    }

    private static PricingRuleExportItem toExportItem(PricingRuleEntity e) {
        PricingRuleExportItem item = new PricingRuleExportItem();
        item.setKind(e.getKind());
        item.setProviderId(e.getProviderId());
        item.setModel(e.getModel());
        item.setHasReference(e.getHasReference() != null && e.getHasReference());
        item.setPriceInputPerMillion(e.getPriceInputPerMillion());
        item.setPriceOutputPerMillion(e.getPriceOutputPerMillion());
        item.setVideoBillingMode(e.getVideoBillingMode());
        item.setPricePerSecond(e.getPricePerSecond());
        item.setPricePerImage(e.getPricePerImage());
        return item;
    }

    public List<AvailablePricingModelVO> availablePricingModels() {
        Set<String> configured = new HashSet<>();
        Set<String> configuredGlobalModels = new HashSet<>();
        for (PricingRuleEntity rule : pricingRuleMapper.selectList(new LambdaQueryWrapper<>())) {
            if (rule.getModel() == null || rule.getModel().isBlank()) {
                continue;
            }
            if (rule.getProviderId() == null) {
                // 兼容 V66 历史全局价：其同名模型对所有供应商都已配置。
                configuredGlobalModels.add(rule.getModel().trim());
            } else {
                // 7x-3：VIDEO 的 has_reference=true/false 视为不同配置行——
                // 一个 VIDEO 模型只配了 false 不应阻止 admin 再配 true，故候选身份带 has_reference 维度。
                // 非 VIDEO 行 has_reference 恒 false，身份退化为原 provider+model（行为不变）。
                configured.add(pricingIdentity(rule.getProviderId(), rule.getModel(),
                        PricingRuleEntity.KIND_VIDEO.equals(rule.getKind())
                                && Boolean.TRUE.equals(rule.getHasReference())));
            }
        }
        // 候选恒按 has_reference=false 出（VIDEO 单行候选），admin 通过表单开关新增 true 变体行
        return llmProviderMapper.selectList(new LambdaQueryWrapper<LlmProviderEntity>()
                        .eq(LlmProviderEntity::getStatus, "ACTIVE"))
                .stream()
                .filter(provider -> "ACTIVE".equals(provider.getStatus()))
                .flatMap(provider -> toAvailableModels(provider))
                .filter(candidate -> !configuredGlobalModels.contains(candidate.getModel())
                        && !configured.contains(
                                pricingIdentity(candidate.getProviderId(), candidate.getModel(), false)))
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

    private String pricingIdentity(Long providerId, String model, boolean hasReference) {
        return providerId + "\u0000" + model.trim() + "\u0000" + (hasReference ? "1" : "0");
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
            case "RERANK" -> PricingRuleEntity.KIND_RERANK;
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
        long duplicateCount = pricingRuleMapper.countConflictingProviderModelHasRef(
                req.getProviderId(), req.getModel().trim(), effectiveHasReference(req));
        if (duplicateCount > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "该全局模型已配置价表（相同参考视频维度）");
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
                || !Objects.equals(e.getKind(), req.getKind())
                || !Objects.equals(e.getHasReference(), effectiveHasReference(req))) {
            // 7x-3：has_reference 视为身份的一部分（VIDEO 不同参考维度是不同行），编辑不可改
            throw new BusinessException(ErrorCode.BAD_REQUEST, "编辑时不可修改 provider/model/kind/hasReference");
        }
        applyRequest(req, e);
        pricingRuleMapper.updateById(e);
        return toVO(e);
    }

    private void validatePricingRule(PricingRuleRequest req) {
        if (req.getKind() == null || !KINDS.contains(req.getKind())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "kind 须为 CHAT/EMBED/RERANK/IMAGE/VIDEO");
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
        // 7x-3：非 VIDEO 强制 has_reference=false（true 仅对 VIDEO 有意义）
        if (!PricingRuleEntity.KIND_VIDEO.equals(req.getKind()) && Boolean.TRUE.equals(req.getHasReference())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "hasReference=true 仅对 VIDEO kind 有效");
        }
    }

    /** has_reference 归一化为 boolean：null 视为 false；非 VIDEO 恒 false。 */
    private boolean effectiveHasReference(PricingRuleRequest req) {
        if (!PricingRuleEntity.KIND_VIDEO.equals(req.getKind())) {
            return false;
        }
        return Boolean.TRUE.equals(req.getHasReference());
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
        e.setHasReference(effectiveHasReference(req));
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
                .hasReference(e.getHasReference() != null && e.getHasReference())
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
