package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.PricingRuleEntity;
import com.superprogrammer.billing.mapper.PricingRuleMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 模型询价服务：按价表把用量折成 ¥（真实金额）。
 * <p>计费链第一步：token/秒/张 →(价表)→ ¥。第二步 ¥→积分 由 {@link PointsRatioService}。
 * <p>各 kind 口径：
 * <ul>
 *   <li>CHAT：(input/1M)×pin + (output/1M)×pout。</li>
 *   <li>EMBED：仅 input 计价（output=0）：(input/1M)×pin。</li>
 *   <li>VIDEO TOKEN 模式：视频总 token × priceInputPerMillion / 1M（caller 把 Ark 返的 total_tokens 传 tokensInput）。</li>
 *   <li>VIDEO SECOND 模式：videoSeconds × pricePerSecond。</li>
 *   <li>IMAGE：imageCount × pricePerImage。</li>
 * </ul>
 * <p>无价表抛 {@link ErrorCode#PRICING_NOT_FOUND}；调用层（gateway/worker）应 catch 降级为「不计费+WARN」，
 * 不阻断核心调用（计费配置缺失不应让对话/视频 500）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingService {

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    private final PricingRuleMapper pricingRuleMapper;
    /** 7x-5（V160 D2）：闲时时段配置读取（每请求实时查，与 daily-cap 同哲学）。 */
    private final com.superprogrammer.system.service.SystemSettingService systemSettingService;

    /**
     * 计算单次调用真实金额（¥）。
     *
     * @param kind          CHAT/EMBED/IMAGE/VIDEO
     * @param providerId    provider DB id（null 走全局价）
     * @param model         模型名
     * @param tokensInput   input token（VIDEO TOKEN 模式传视频总 token）
     * @param tokensOutput  output token（EMBED/VIDEO 不用）
     * @param videoSeconds  视频秒数（VIDEO SECOND 模式用）
     * @param imageCount    图片张数（IMAGE 用）
     * @return 真实金额 ¥（6 位小数，对齐 cost_yuan NUMERIC(12,6)）
     * @deprecated 使用 {@link #computeCost(String, Long, String, Integer, Integer, Integer, Integer, boolean)}
     *             显式传 hasReference。本重载恒按无参考计价，仅向后兼容旧调用点。
     */
    @Deprecated
    public BigDecimal computeCost(String kind, Long providerId, String model,
                                  Integer tokensInput, Integer tokensOutput,
                                  Integer videoSeconds, Integer imageCount) {
        return computeCost(kind, providerId, model, tokensInput, tokensOutput,
                videoSeconds, imageCount, false);
    }

    /**
     * 计算单次调用真实金额（¥），VIDEO 支持按 hasReference 区分参考视频价。
     *
     * @param hasReference  VIDEO 任务是否带参考视频（其他 kind 忽略，恒按 false 查）。
     *                      VIDEO 查不到精确行时回退 false 行（兜底），再查不到抛 PRICING_NOT_FOUND。
     */
    public BigDecimal computeCost(String kind, Long providerId, String model,
                                  Integer tokensInput, Integer tokensOutput,
                                  Integer videoSeconds, Integer imageCount,
                                  boolean hasReference) {
        return computeCost(kind, providerId, model, tokensInput, tokensOutput,
                videoSeconds, imageCount, hasReference, null);
    }

    /**
     * 7x-1（V152）：+resolution 版本。VIDEO SECOND 模式按分辨率行计价；
     * 其他 kind / VIDEO TOKEN 忽略（其行 resolution 恒 NULL，传值不命中也无妨——归一为 null 查）。
     * <p>VIDEO 命中链：精确(参考面,分辨率) → (参考面,通用NULL行) → (无参考,分辨率) → (无参考,通用)。
     */
    public BigDecimal computeCost(String kind, Long providerId, String model,
                                  Integer tokensInput, Integer tokensOutput,
                                  Integer videoSeconds, Integer imageCount,
                                  boolean hasReference, String resolution) {
        return computeCost(kind, providerId, model, tokensInput, tokensOutput,
                videoSeconds, imageCount, hasReference, resolution, null);
    }

    /**
     * 9x-1（V160 D2）：+cachedTokens 版本——缓存命中读 token 作第三腿单独计价。
     * 口径（规格 §6.3，两家协议已在 Provider 层归一）：
     * tokensInput=未命中输入（OpenAI=prompt−cached；Claude=input+cache_creation）；
     * cachedTokens=命中读（OpenAI=cached_tokens；Claude=cache_read_input_tokens）。
     * cachedTokens=null/0 → 计费退化为输入+输出两腿（老链路与老价表逐分一致）。
     */
    public BigDecimal computeCost(String kind, Long providerId, String model,
                                  Integer tokensInput, Integer tokensOutput,
                                  Integer videoSeconds, Integer imageCount,
                                  boolean hasReference, String resolution,
                                  Long cachedTokens) {
        return computeCostAt(kind, providerId, model, tokensInput, tokensOutput,
                videoSeconds, imageCount, hasReference, resolution, cachedTokens, null);
    }

    /**
     * 7x-5（V160 D2）：+moment 版本（单测/回算用显式时刻判闲时；null=now）。
     * 闲时判定读 system_settings 键 billing.off-peak.schedule（每请求实时查，与 daily-cap 同哲学；
     * 计费频次=结算频次（低频），不加缓存）。
     */
    public BigDecimal computeCostAt(String kind, Long providerId, String model,
                                    Integer tokensInput, Integer tokensOutput,
                                    Integer videoSeconds, Integer imageCount,
                                    boolean hasReference, String resolution,
                                    Long cachedTokens, java.time.LocalDateTime moment) {
        boolean isVideo = PricingRuleEntity.KIND_VIDEO.equals(kind);
        boolean effectiveHasRef = isVideo && hasReference;
        String effectiveResolution = isVideo ? normalizeResolution(resolution) : null;
        PricingRuleEntity rule = resolveRule(kind, providerId, model, effectiveHasRef, effectiveResolution);
        if (rule == null) {
            throw new BusinessException(ErrorCode.PRICING_NOT_FOUND,
                    "未配置价表: kind=" + kind + " providerId=" + providerId
                            + " model=" + model + " hasReference=" + hasReference
                            + " resolution=" + resolution);
        }
        java.time.LocalDateTime at = moment != null ? moment : java.time.LocalDateTime.now();
        return switch (kind) {
            case PricingRuleEntity.KIND_CHAT ->
                    textCost(rule, tokensInput, cachedTokens, tokensOutput, true, at);
            case PricingRuleEntity.KIND_EMBED ->
                    textCost(rule, tokensInput, null, tokensOutput, false, at);
            case PricingRuleEntity.KIND_RERANK ->
                    textCost(rule, tokensInput, null, tokensOutput, false, at);
            case PricingRuleEntity.KIND_VIDEO -> videoCost(rule, tokensInput, videoSeconds, effectiveResolution);
            // resolution 双用：resolveRule 命中链（SECOND 分辨率行，V152）+ TOKEN 行内槽位取价（V162）
            case PricingRuleEntity.KIND_IMAGE -> imageCost(rule, imageCount);
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "未知计费 kind: " + kind);
        };
    }

    /**
     * 7x-1（V152）：VIDEO 价表行命中链——
     * 精确(参考面,分辨率) → (参考面,通用NULL行) → (无参考,分辨率) → (无参考,通用NULL行)。
     * 非 VIDEO 退化为原 (kind,provider,model,false,NULL) 单次精确查。
     */
    private PricingRuleEntity resolveRule(String kind, Long providerId, String model,
                                          boolean hasRef, String resolution) {
        PricingRuleEntity rule = pricingRuleMapper.findEffectiveWithResolution(
                kind, providerId, model, hasRef, resolution);
        if (rule == null && resolution != null) {
            // 该分辨率未单列 → 通用行兜底（admin 只配一行 NULL 即可覆盖所有分辨率）
            rule = pricingRuleMapper.findEffectiveWithResolution(kind, providerId, model, hasRef, null);
        }
        // 7x-3 fallback：有参考精确查不到 → 回退无参考行（分辨率同样先精确后通用）
        if (rule == null && hasRef) {
            rule = pricingRuleMapper.findEffectiveWithResolution(kind, providerId, model, false, resolution);
            if (rule == null && resolution != null) {
                rule = pricingRuleMapper.findEffectiveWithResolution(kind, providerId, model, false, null);
            }
        }
        return rule;
    }

    /** resolution 归一化：trim+小写（4K→4k，与价表落库口径一致）；空串视 null（=通用行）。 */
    static String normalizeResolution(String resolution) {
        if (resolution == null || resolution.isBlank()) {
            return null;
        }
        return resolution.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 7x-2（V152）：视频提交期估价（¥ 口径，caller 折积分）。
     * SECOND 模式 = 命中行 pricePerSecond × 时长（分辨率精确/通用兜底同真实扣费链）；
     * TOKEN 模式提交期无 token 维度 = 命中行 estYuanPerSecond × 时长。
     * 无价表行 / SECOND 秒价未配 / TOKEN 预估秒价未配 → 抛 {@link ErrorCode#PRICING_NOT_FOUND}
     * （2026-08-25 fail-closed：估不出价不许静默记 0——估价 0 会跳过预检与预扣放白嫖，
     *  提交侧必须拒单，caller 不得吞；预估预览除外，catch 记 0）。
     * 显式配 0 价（免费模型）返 0，由提交侧硬闸统一拒（免费请配极小正值）。
     */
    public BigDecimal estimateVideoYuan(Long providerId, String model,
                                        Integer seconds, String resolution, boolean hasReference) {
        PricingRuleEntity rule = resolveRule(PricingRuleEntity.KIND_VIDEO, providerId, model,
                hasReference, normalizeResolution(resolution));
        if (rule == null) {
            throw new BusinessException(ErrorCode.PRICING_NOT_FOUND,
                    "未配置价表: kind=VIDEO providerId=" + providerId + " model=" + model
                            + " hasReference=" + hasReference + " resolution=" + resolution);
        }
        if (seconds == null || seconds <= 0) {
            return BigDecimal.ZERO;
        }
        if (PricingRuleEntity.VIDEO_MODE_SECOND.equals(rule.getVideoBillingMode())) {
            if (rule.getPricePerSecond() == null) {
                throw new BusinessException(ErrorCode.PRICING_NOT_FOUND,
                        "VIDEO SECOND 模式未配置 price_per_second: model=" + model);
            }
            return rule.getPricePerSecond().multiply(BigDecimal.valueOf(seconds))
                    .setScale(6, RoundingMode.HALF_UP);
        }
        // TOKEN 模式：预估秒价（按分辨率参数，V153）× 时长（仅估价，不参与真实扣费）
        BigDecimal est = resolveEstPerSecond(rule.getEstPerResolution(),
                normalizeResolution(resolution));
        if (est == null) {
            throw new BusinessException(ErrorCode.PRICING_NOT_FOUND,
                    "VIDEO TOKEN 模式未配置 est_per_resolution 预估秒价: model=" + model
                            + " hasReference=" + hasReference + " resolution=" + resolution);
        }
        return est.multiply(BigDecimal.valueOf(seconds)).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 7x-2（V153）：从 est_per_resolution JSON 取「任务分辨率对应预估值」——
     * 精确分辨率键 → general 兜底键 → null（未配置=不可估，caller 按 0 放行）。
     * JSON 损坏/值非法 → null + WARN（估价旁路绝不阻断提交）。
     */
    private static BigDecimal resolveEstPerSecond(String estPerResolutionJson, String resolution) {
        if (estPerResolutionJson == null || estPerResolutionJson.isBlank()) {
            return null;
        }
        try {
            java.util.Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(estPerResolutionJson,
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { });
            Object v = resolution != null ? map.get(resolution) : null;
            if (v == null) {
                v = map.get("general");
            }
            if (v == null) {
                return null;
            }
            BigDecimal est = new BigDecimal(String.valueOf(v));
            return est.signum() >= 0 ? est : null;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PricingService.class)
                    .warn("est_per_resolution 解析失败按不可估处理: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 文本/embed/rerank 询价（V160 D2 三腿 + 闲时）：
     * in×pIn + cached×pCache + out×pOut（各腿价按时刻挑忙/闲列）。
     * billOutput=false（embed/rerank）忽略 output；cached=null/0 时第三腿消失（两腿，老口径）。
     * 闲时价列 NULL → 取对应忙时列（存量价表逐分不变的保证）。
     */
    private BigDecimal textCost(PricingRuleEntity rule, Integer in, Long cached, Integer out,
                                boolean billOutput, java.time.LocalDateTime moment) {
        boolean offPeak = isOffPeak(moment);
        BigDecimal cost = BigDecimal.ZERO;
        if (in != null && in > 0) {
            BigDecimal pIn = pickPrice(offPeak,
                    rule.getPriceInputPerMillion(), rule.getOffPeakInputPerMillion());
            if (pIn != null) {
                cost = cost.add(price(pIn, in));
            }
        }
        if (cached != null && cached > 0) {
            // 缓存价回落链：闲时缓存价 →（忙时）缓存价 → 当前输入价（含闲时输入价）
            BigDecimal pCache = pickPrice(offPeak,
                    rule.getPriceCachedPerMillion(), rule.getOffPeakCachedPerMillion());
            if (pCache == null) {
                pCache = pickPrice(offPeak,
                        rule.getPriceInputPerMillion(), rule.getOffPeakInputPerMillion());
            }
            if (pCache != null) {
                cost = cost.add(price(pCache, cached.intValue()));
            }
        }
        if (billOutput && out != null && out > 0) {
            BigDecimal pOut = pickPrice(offPeak,
                    rule.getPriceOutputPerMillion(), rule.getOffPeakOutputPerMillion());
            if (pOut != null) {
                cost = cost.add(price(pOut, out));
            }
        }
        return cost;
    }

    /** 闲时取闲列，忙时（或闲列 NULL）取忙列。 */
    private static BigDecimal pickPrice(boolean offPeak, BigDecimal busy, BigDecimal offPeakPrice) {
        if (offPeak && offPeakPrice != null) {
            return offPeakPrice;
        }
        return busy;
    }

    // ==================== 7x-5（V160 D2）：闲时时段判定 ====================

    /** system_settings 键（D8 配置页同键读写）。 */
    public static final String OFF_PEAK_SCHEDULE_KEY = "billing.off-peak.schedule";

    private static final com.fasterxml.jackson.databind.ObjectMapper OFF_PEAK_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 时刻是否落在闲时窗口。配置缺失/enabled=false/JSON 非法 → false（回退忙时，宁多收不少收）。
     * 周末判定按 Asia/Shanghai（业务时区）；跨零点窗口（end<=start，如 22:00-08:00）拆
     * [start,24:00)+[00:00,end) 两段判断。
     */
    boolean isOffPeak(java.time.LocalDateTime moment) {
        String json;
        try {
            json = systemSettingService.getSettingValue(OFF_PEAK_SCHEDULE_KEY);
        } catch (Exception e) {
            log.warn("读闲时配置失败(回退忙时) : {}", e.toString());
            return false;
        }
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            OffPeakSchedule cfg = OFF_PEAK_MAPPER.readValue(json, OffPeakSchedule.class);
            if (cfg == null || !cfg.enabled || cfg.weekday == null || cfg.weekday.isEmpty()) {
                return false;
            }
            java.time.ZonedDateTime shanghai = moment.atZone(java.time.ZoneId.of("Asia/Shanghai"));
            boolean weekend = shanghai.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || shanghai.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
            java.util.List<OffPeakWindow> windows = weekend ? cfg.weekend : cfg.weekday;
            if (windows == null || windows.isEmpty()) {
                // 周末没配 → 用工作日窗口（常见：周末全天闲时另配，否则沿用）
                windows = cfg.weekday;
            }
            int minute = shanghai.getHour() * 60 + shanghai.getMinute();
            for (OffPeakWindow w : windows) {
                Integer start = parseHm(w.start);
                Integer end = parseHm(w.end);
                if (start == null || end == null) {
                    continue;
                }
                if (end <= start) {
                    // 跨零点：[start,24:00) ∪ [00:00,end)
                    if (minute >= start || minute < end) {
                        return true;
                    }
                } else if (minute >= start && minute < end) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("闲时配置解析失败(回退忙时) : {}", e.toString());
            return false;
        }
    }

    /** "HH:mm" → 当日分钟数；非法返 null。 */
    private static Integer parseHm(String hm) {
        if (hm == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(hm.trim());
        if (!m.matches()) {
            return null;
        }
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h > 24 || min > 59) {
            return null;
        }
        return h * 60 + min;
    }

    /** 闲时配置 JSON 形（D8 配置页读写同构）。 */
    public static class OffPeakSchedule {
        public boolean enabled;
        public String timezone;
        public java.util.List<OffPeakWindow> weekday;
        public java.util.List<OffPeakWindow> weekend;
    }

    public static class OffPeakWindow {
        public String start;
        public String end;
    }

    /**
     * 视频询价：SECOND 模式按秒；TOKEN 模式按 token，每百万价 = 分档槽位价 ?? 通用价（V162）。
     * resolution 由 caller 传归一后键（computeCostAt :116 normalizeResolution 产物）。
     */
    private BigDecimal videoCost(PricingRuleEntity rule, Integer tokens, Integer seconds, String resolution) {
        if (PricingRuleEntity.VIDEO_MODE_SECOND.equals(rule.getVideoBillingMode())) {
            if (seconds == null || seconds <= 0 || rule.getPricePerSecond() == null) {
                return BigDecimal.ZERO;
            }
            return rule.getPricePerSecond().multiply(BigDecimal.valueOf(seconds)).setScale(6, RoundingMode.HALF_UP);
        }
        // TOKEN 模式（默认）：视频总 token × 每百万价 / 1M。
        // 每百万价 = token_price_per_resolution[resolution] ?? priceInputPerMillion（未配档回落通用价，V162）；
        // 槽+通用全无 → 0 元交付（缺价=0 元现状口径不变，规格 VTR-2）。
        BigDecimal pricePerMillion = resolveTokenPricePerMillion(rule.getTokenPricePerResolution(), resolution);
        if (pricePerMillion == null) {
            pricePerMillion = rule.getPriceInputPerMillion();
        }
        if (tokens == null || tokens <= 0 || pricePerMillion == null) {
            return BigDecimal.ZERO;
        }
        return price(pricePerMillion, tokens);
    }

    /**
     * V162：从 token_price_per_resolution JSON 取「任务分辨率档位每百万价」——
     * 键 ⊆ 480p/720p/768p/1080p/2k/4k（无 general 兜底键，回落通用价由 caller 取 priceInputPerMillion）。
     * resolution null（未传）/未配档 → null；JSON 损坏 → null + WARN（结算回落通用价，脏价表不废任务）。
     */
    private static BigDecimal resolveTokenPricePerMillion(String tokenPricePerResolutionJson, String resolution) {
        if (tokenPricePerResolutionJson == null || tokenPricePerResolutionJson.isBlank() || resolution == null) {
            return null;
        }
        try {
            java.util.Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(tokenPricePerResolutionJson,
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { });
            Object v = map.get(resolution);
            return v == null ? null : new BigDecimal(String.valueOf(v));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PricingService.class)
                    .warn("token_price_per_resolution 解析失败回落通用价: {}", e.getMessage());
            return null;
        }
    }

    /** 图片询价：张数 × 单价。 */
    private BigDecimal imageCost(PricingRuleEntity rule, Integer count) {
        if (count == null || count <= 0 || rule.getPricePerImage() == null) {
            return BigDecimal.ZERO;
        }
        return rule.getPricePerImage().multiply(BigDecimal.valueOf(count)).setScale(6, RoundingMode.HALF_UP);
    }

    /** pricePerMillion × tokens / 1M。 */
    private BigDecimal price(BigDecimal pricePerMillion, int tokens) {
        return pricePerMillion
                .multiply(BigDecimal.valueOf(tokens))
                .divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
    }
}
