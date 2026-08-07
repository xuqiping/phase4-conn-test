package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * LLM 出口计费编排（spec §4 计费链 + §5 两写路径）：算价 → 折算 → 同步扣 → 异步采。
 * <p>每个 LLM 出口（chat/chatStream/embed/video/image）调用成功/失败各调一次本服务，
 * 出口本身保持纤薄。<b>铁律：计费全链 try/catch 吞异常，绝不抛回 LLM 出口</b>——
 * 计费是 LLM 服务的旁路，价表缺失/DB 抖动不得让已成功的 LLM 回答丢给用户、不得回归 13 个调用方。
 * <p>决策（spec §5 决策1）：扣减走 {@link PointsWalletService}（同步事务，不可丢）；
 * 审计走 {@link UsageCollector}（异步 fire-and-forget，可丢）。二者各失败各降级，互不影响对账。
 * <p>userId=null（系统调用，如 RAG 内部 embed）→ {@code charge} 自然短路（仅采不扣）。
 * <p>视频/图片的 token 维度为 null，cost 由其出口用 secs/count 维度算好传入 costYuan；
 * 本服务的 onSuccess 走 token 维度，故视频/图片出口直接调 {@link UsageCollector}+{@link PointsWalletService}
 * （见 Chunk F），不走本 token 路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmBillingService {

    private final PricingService pricingService;
    private final PointsRatioService ratioService;
    private final PointsWalletService walletService;
    private final UsageCollector usageCollector;

    /**
     * LLM 调用成功：算价→折算→同步扣→异步采。全链吞异常。
     *
     * @param kind {@link LlmUsageLogEntity#KIND_CHAT}/EMBED（视频/图片走 Chunk F，不经此 token 路径）
     * @return 扣后余额；未扣（系统调用/disabled/计费失败）返 null
     */
    public BigDecimal onSuccess(Long userId, Long providerId, String providerScope, String model, String kind,
                                Integer tokensInput, Integer tokensOutput) {
        try {
            BigDecimal yuan = pricingService.computeCost(kind, providerId, model,
                    tokensInput, tokensOutput, 0, 0);
            BigDecimal points = ratioService.toPoints(yuan);
            // refType = kind（CHAT/EMBED，与 ledger REF_* 同串）；refId 暂无单次调用 id
            BigDecimal after = walletService.charge(userId, points, kind, null, model);
            usageCollector.record(userId, providerId, providerScope, model, kind,
                    tokensInput, tokensOutput, yuan, points, LlmUsageLogEntity.STATUS_SUCCESS, null);
            return after;
        } catch (BusinessException e) {
            // 计费自身失败（PRICING_NOT_FOUND 等）：LLM 已答完不可逆，记 FAILED usage 让 admin 可见缺口，不抛
            usageCollector.record(userId, providerId, providerScope, model, kind,
                    tokensInput, tokensOutput, null, null, LlmUsageLogEntity.STATUS_FAILED, e.getMessage());
            log.warn("计费失败(已记FAILED,不阻塞LLM) userId={} model={} kind={} : {}",
                    userId, model, kind, e.toString());
            return null;
        } catch (Exception e) {
            // 兜底：任何意外都不许回归 LLM 出口
            log.warn("计费意外异常(吞) userId={} model={} : {}", userId, model, e.toString());
            return null;
        }
    }

    /**
     * LLM 调用失败：没扣到不用退，仅采一条 FAILED usage 供 admin 排障。吞异常。
     */
    public void onFailure(Long userId, Long providerId, String providerScope, String model, String kind,
                          String errorMsg) {
        try {
            usageCollector.record(userId, providerId, providerScope, model, kind,
                    null, null, null, null, LlmUsageLogEntity.STATUS_FAILED, errorMsg);
        } catch (Exception e) {
            log.warn("失败 usage 采集异常(吞) : {}", e.toString());
        }
    }
}
