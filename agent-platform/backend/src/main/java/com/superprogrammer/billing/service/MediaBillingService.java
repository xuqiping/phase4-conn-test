package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 媒体（视频/图片）出口计费编排（Chunk F/G，spec §4 计费链）。
 *
 * <p>与 {@link LlmBillingService} 并列但维度不同：文本走 token 维度（{@code onSuccess}），
 * 媒体走 **secs / count / 视频伪-token** 维度（本服务）。视频 token 口径刻意与文本分词隔离——
 * Ark 返的 {@code total_tokens} 是像素×帧×时长换算的「伪 token」，<b>不可与文本加总</b>，
 * 故 {@code usage_logs.kind=VIDEO/IMAGE} 单列，admin 查询层按 kind 分组（spec §坑点「视频 token 口径≠文本」）。
 *
 * <p>计费链同 LLM：算价({@link PricingService}) → 折算({@link PointsRatioService}) →
 * 同步扣({@link PointsWalletService}) → 异步采({@link UsageCollector})。
 * <b>铁律同 LLM：计费全链 try/catch 吞异常，绝不抛回媒体出口</b>——
 * 计费是媒体生成的旁路，价表缺失/DB 抖动不得让已成功的视频落不了 SUCCEEDED、不得回归调用方。
 *
 * <p>调用时机（plan §Step13）：视频 {@code markSucceeded} <b>前</b>扣（扣后若 markSucceeded 落库失败由 worker
 * 调 {@link #refundMedia} 撤销）；submit 入口预检余额&gt;0（在 {@code MediaGenTaskService} 调
 * {@link PointsWalletService#requireAffordable}）。失败（ark 失败/超时/下载失败）路径本就没扣→不退。
 *
 * <p>userId=null（系统调用）→ {@code chargeMedia} 自然短路（wallet 仅采不扣）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaBillingService {

    private final PricingService pricingService;
    private final PointsRatioService ratioService;
    private final PointsWalletService walletService;
    private final UsageCollector usageCollector;

    /**
     * 媒体调用成功计费：算价→折算→同步扣→异步采。全链吞异常。
     *
     * <p>VIDEO：{@code tokensInput} 传 Ark 真值/费率估算的视频伪-token，{@code videoSeconds} 传时长；
     * {@link PricingService#videoCost} 据价表 {@code video_billing_mode} 选 TOKEN（按 token）或 SECOND（按秒）计价。
     * IMAGE：{@code imageCount} 传张数。
     *
     * @param kind         {@link LlmUsageLogEntity#KIND_VIDEO}/IMAGE
     * @param tokensInput  视频伪-token（Ark 真值/估算；IMAGE 不用传 null）
     * @param videoSeconds 视频秒数（SECOND 模式用；IMAGE 不用传 null/0）
     * @param imageCount   图片张数（IMAGE 用；VIDEO 传 0）
     * @param status       {@link LlmUsageLogEntity#STATUS_SUCCESS}/ESTIMATED（估算口径仍计费）
     * @param refId        任务 id（落 ledger/usage 引用，便于对账追溯）
     * @return 实际扣减的积分（正数，供 worker 在 markSucceeded 失败时退款）；未扣返 null
     * @deprecated 使用 {@link #chargeMedia(Long, Long, String, String, Integer, Integer, Integer, String, Long, boolean)}
     *             传 hasReference。本重载恒按无参考计价，仅向后兼容。
     */
    @Deprecated
    public BigDecimal chargeMedia(Long userId, Long providerId, String model, String kind,
                                  Integer tokensInput, Integer videoSeconds, Integer imageCount,
                                  String status, Long refId) {
        return chargeMedia(userId, providerId, model, kind, tokensInput, videoSeconds, imageCount,
                status, refId, false);
    }

    /**
     * 媒体调用成功计费：算价→折算→同步扣→异步采。全链吞异常。
     *
     * @param hasReference 7x-3：VIDEO 任务是否带参考视频（worker 从 attachments kind=="video" 算）。
     *                     IMAGE/其他 kind 忽略，恒按 false 计价。
     */
    public BigDecimal chargeMedia(Long userId, Long providerId, String model, String kind,
                                  Integer tokensInput, Integer videoSeconds, Integer imageCount,
                                  String status, Long refId, boolean hasReference) {
        if (!walletService.isEnabled()) {
            return null;
        }
        try {
            BigDecimal yuan = pricingService.computeCost(kind, providerId, model,
                    tokensInput, null, videoSeconds, imageCount, hasReference);
            BigDecimal points = ratioService.toPoints(yuan);
            // refType=kind(VIDEO/IMAGE)，refId=任务 id；charge 内部已 insertIfAbsent+行锁+流水(CONSUME)
            walletService.charge(userId, points, kind, refId, model);
            // 8x Chunk7：taskId=refId（任务 id）落 usage 行，媒体审计两行 targetId=taskId 与此对齐做 drill-down
            usageCollector.record(userId, providerId, LlmUsageLogEntity.SCOPE_GLOBAL, model, kind,
                    tokensInput, null, yuan, points, status, null, refId);
            return points;
        } catch (BusinessException e) {
            // 计费自身失败（价表缺/余额在生成期间被耗尽等）：视频已生成不可逆，记 FAILED usage 让 admin 可见缺口，不抛
            usageCollector.record(userId, providerId, LlmUsageLogEntity.SCOPE_GLOBAL, model, kind,
                    tokensInput, null, null, null, LlmUsageLogEntity.STATUS_FAILED, e.toString(), refId);
            log.warn("媒体计费失败(已记FAILED,不阻塞媒体出口) userId={} model={} kind={} refId={} : {}",
                    userId, model, kind, refId, e.toString());
            return null;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Phase4 审查修正：refId=任务 id 锚定 uq_ledger_ref，重复扣减被唯一约束拦下会落进这里——
            // 这不是失败而是「恰好一次」语义生效，单列日志不与真实失败混淆（对账时不计缺口）。
            log.info("媒体计费疑似重复扣减被唯一约束拦截(恰好一次生效,非失败) userId={} model={} kind={} refId={} : {}",
                    userId, model, kind, refId, e.toString());
            return null;
        } catch (Exception e) {
            // 兜底：任何意外都不许回归媒体出口
            log.warn("媒体计费意外异常(吞) userId={} model={} kind={} refId={} : {}",
                    userId, model, kind, refId, e.toString());
            return null;
        }
    }

    /**
     * 退款：{@code markSucceeded} 落库失败时撤销已扣积分（防对账黑洞）。吞异常。
     *
     * <p>仅当 {@link #chargeMedia} 返回了正数积分（确认扣过）才调；失败路径本没扣不调。
     */
    public void refundMedia(Long userId, BigDecimal chargedPoints, String kind, Long refId) {
        if (chargedPoints == null || chargedPoints.signum() <= 0) {
            return;
        }
        try {
            walletService.refund(userId, chargedPoints, kind, refId, "媒体任务落库失败退款");
        } catch (Exception e) {
            log.warn("媒体退款异常(吞) userId={} kind={} refId={} : {}", userId, kind, refId, e.toString());
        }
    }
}
