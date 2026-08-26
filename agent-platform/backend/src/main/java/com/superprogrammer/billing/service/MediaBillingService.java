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
    /** 计划5 Step5：组池结算分支（chargeGroup/refundGroup/backstop）。 */
    private final com.superprogrammer.projectgroup.service.ProjectGroupWalletService groupWalletService;
    /** 计划5 Step5：BACKSTOP 兜底取组长（组行 owner）。 */
    private final com.superprogrammer.projectgroup.mapper.ProjectGroupMapper groupMapper;
    private final UsageCollector usageCollector;

    /**
     * 11x 加固 P3-C9：媒体扣费成功发 KIND_MEDIA_SUBMIT（媒体滥用规则消费，cost=实扣积分）。
     * 字段注入 required=false——横切可选依赖，既有 @InjectMocks 单测无此 Bean 时跳过（防构造参涟漪）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.superprogrammer.common.security.SecurityEventPublisher securityEventPublisher;

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
        return chargeMedia(userId, providerId, model, kind, tokensInput, videoSeconds, imageCount,
                status, refId, hasReference, null);
    }

    /**
     * 计划5 Step5：+projectGroupId 组池结算版本。gid 非空且 uid 非空 →
     * {@code chargeGroup}（幂等键=media-charge-{taskId}，429 退避重投不双扣）；残余竞态
     * （提交预检已过、结算时组池尽/超限额）→ <b>BACKSTOP 兜底</b>：成本已真实发生（视频已生成），
     * 差额扣组长个人 + 组流水 BACKSTOP + 计入消费成员 used（7x-2：used=真实消耗，不论资金来源；
     * 组池 balance 不含 BACKSTOP）——与「记 FAILED 让平台亏钱」二选一，媒体语义取兜底；
     * 组长个人也不足才落 FAILED usage。gid 空 → 个人 charge 现状。
     */
    public BigDecimal chargeMedia(Long userId, Long providerId, String model, String kind,
                                  Integer tokensInput, Integer videoSeconds, Integer imageCount,
                                  String status, Long refId, boolean hasReference, Long projectGroupId) {
        return chargeMedia(userId, providerId, model, kind, tokensInput, videoSeconds, imageCount,
                status, refId, hasReference, projectGroupId, null);
    }

    /**
     * 7x-1（V152）：+resolution 版本。VIDEO SECOND 模式按任务实际分辨率命中分辨率价行
     * （未单列回落通用 NULL 行）；其他 kind / TOKEN 模式传 null 即可（行为同前）。
     */
    public BigDecimal chargeMedia(Long userId, Long providerId, String model, String kind,
                                  Integer tokensInput, Integer videoSeconds, Integer imageCount,
                                  String status, Long refId, boolean hasReference, Long projectGroupId,
                                  String resolution) {
        if (!walletService.isEnabled()) {
            return null;
        }
        try {
            BigDecimal yuan = pricingService.computeCost(kind, providerId, model,
                    tokensInput, null, videoSeconds, imageCount, hasReference, resolution);
            BigDecimal points = ratioService.toPoints(yuan);
            if (projectGroupId != null && userId != null && points != null && points.signum() > 0) {
                try {
                    groupWalletService.chargeGroup(projectGroupId, userId, points, kind,
                            String.valueOf(refId), "media-charge-" + refId);
                } catch (BusinessException be) {
                    // BACKSTOP：组长兜底全差额（be=组池尽/超限额残余竞态）；再失败（组长也尽）抛给外层 FAILED
                    backstopMedia(projectGroupId, userId, points, kind, refId);
                    log.warn("媒体组结算转兜底 groupId={} userId={} points={} ref={} : {}",
                            projectGroupId, userId, points, refId, be.getMessage());
                }
            } else {
                // refType=kind(VIDEO/IMAGE)，refId=任务 id；charge 内部已 insertIfAbsent+行锁+流水(CONSUME)
                walletService.charge(userId, points, kind, refId, model);
            }
            // 11x P3-C9：扣费成功发媒体滥用事件（worker 线程无 request → ip=null，按用户维度计数）
            if (securityEventPublisher != null && userId != null && points != null && points.signum() > 0) {
                securityEventPublisher.publish(
                        com.superprogrammer.common.security.event.ApplicationSecurityEvent.KIND_MEDIA_SUBMIT,
                        userId, java.util.Map.of("estimatedCostFen", points.longValue(), "taskCount", 1));
            }
            // 8x Chunk7：taskId=refId（任务 id）落 usage 行，媒体审计两行 targetId=taskId 与此对齐做 drill-down
            // 计划5 Step5：gid 落 llm_usage_logs.project_group_id（账单/项目推进唯一事实源；含 BACKSTOP 行）
            usageCollector.record(userId, providerId, LlmUsageLogEntity.SCOPE_GLOBAL, model, kind,
                    tokensInput, null, yuan, points, status, null, refId, null, projectGroupId);
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
        refundMedia(userId, chargedPoints, kind, refId, null);
    }

    /**
     * 计划5 Step5：+projectGroupId 组池退款版本——{@code refundGroup}（组池+used 回减+REFUND 流水，
     * 幂等键=media-refund-{taskId}）；gid 空 → 个人 refund 现状。吞异常。
     */
    public void refundMedia(Long userId, BigDecimal chargedPoints, String kind, Long refId,
                            Long projectGroupId) {
        if (chargedPoints == null || chargedPoints.signum() <= 0) {
            return;
        }
        try {
            if (projectGroupId != null && userId != null) {
                groupWalletService.refundGroup(projectGroupId, userId, chargedPoints, kind,
                        String.valueOf(refId), "media-refund-" + refId);
            } else {
                walletService.refund(userId, chargedPoints, kind, refId, "媒体任务落库失败退款");
            }
        } catch (Exception e) {
            log.warn("媒体退款异常(吞) userId={} kind={} refId={} gid={} : {}",
                    userId, kind, refId, projectGroupId, e.toString());
        }
    }

    // ==================== 7x 预扣 + 多退少补（V155） ====================

    /**
     * 提交期预估预扣：个人钱包 kind-HOLD CONSUME 流水 / 组池 chargeGroup 预扣（组池 + 成员 used 同步预占）。
     * 完工按实 settleMediaSuccess 多退少补；失败 refundMediaHold 全额退。防多任务并行「预检都过、结算不够扣」。
     *
     * 预扣失败（余额/组池在预检后被并发耗尽、超成员限额）直接抛 BusinessException——
     * 提交侧软删任务行并拒绝（语义同原 40201 任务未提交）。不吞异常。
     *
     * @return 是否真预扣（计费开关关/系统调用/估价≤0 → false，worker 据此走原全量结算）
     */
    public boolean holdMediaEstimated(Long userId, BigDecimal estimatedPoints, String kind,
                                      Long taskId, Long projectGroupId) {
        if (!walletService.isEnabled() || userId == null
                || estimatedPoints == null || estimatedPoints.signum() <= 0) {
            return false;
        }
        if (projectGroupId != null) {
            groupWalletService.chargeGroup(projectGroupId, userId, estimatedPoints, kind + "-HOLD",
                    String.valueOf(taskId), "media-hold-" + taskId);
        } else {
            walletService.chargeIdempotent(userId, estimatedPoints, kind + "-HOLD", taskId,
                    "媒体任务预估预扣（完工按实多退少补）", "media-hold-" + taskId);
        }
        return true;
    }

    /**
     * 完工结算（7x 多退少补）：实耗 A 与预扣 E 比差额——A>E 补扣差额 / A<E 退差额 / 相等不动。
     * 未预扣（heldPoints≤0，存量任务/估价 0）→ 委托原 chargeMedia 全量结算。
     *
     * 流水槽位（个人账本 uq_ledger_ref(ref_type,ref_id,type) 恰好各占一格）：
     * 预扣 CONSUME(kind-HOLD) → 补扣 CONSUME(kind) → 退差 REFUND(kind)；
     * 落库失败撤销 REFUND(kind-HOLD)+REFUND(kind)；失败退预扣 REFUND(kind-HOLD)。组账本无唯一约束，同槽位口径。
     *
     * 补扣失败（结算时余额/组池被并发耗尽）：实耗已发生不可撤回，记 FAILED usage 平台可见缺口，
     * 返回已确认扣过的预扣额（worker 在 markSucceeded 失败时据此正确 unwind）。
     *
     * @return 实耗积分（正常结算）；补扣失败返回预扣额；未扣返 null
     */
    public BigDecimal settleMediaSuccess(Long userId, Long providerId, String model, String kind,
                                         Integer tokensInput, Integer videoSeconds, Integer imageCount,
                                         String status, Long refId, boolean hasReference,
                                         Long projectGroupId, String resolution, BigDecimal heldPoints) {
        if (heldPoints == null || heldPoints.signum() <= 0) {
            return chargeMedia(userId, providerId, model, kind, tokensInput, videoSeconds, imageCount,
                    status, refId, hasReference, projectGroupId, resolution);
        }
        if (!walletService.isEnabled() || userId == null) {
            return null;
        }
        try {
            BigDecimal yuan = pricingService.computeCost(kind, providerId, model,
                    tokensInput, null, videoSeconds, imageCount, hasReference, resolution);
            BigDecimal points = ratioService.toPoints(yuan);
            BigDecimal actual = points == null ? BigDecimal.ZERO : points;
            BigDecimal diff = actual.subtract(heldPoints);
            if (diff.signum() > 0) {
                // 实耗超预估 → 补扣差额
                if (projectGroupId != null) {
                    try {
                        groupWalletService.chargeGroup(projectGroupId, userId, diff, kind,
                                String.valueOf(refId), "media-settle-" + refId);
                    } catch (BusinessException be) {
                        // BACKSTOP：组池尽/超限额残余竞态 → 差额扣组长个人（同 chargeMedia 口径）
                        backstopMedia(projectGroupId, userId, diff, kind, refId);
                        log.warn("媒体结算补扣转兜底 groupId={} userId={} diff={} ref={} : {}",
                                projectGroupId, userId, diff, refId, be.getMessage());
                    }
                } else {
                    try {
                        walletService.chargeIdempotent(userId, diff, kind, refId,
                                "媒体任务预估补扣（实耗超预估）", "media-settle-" + refId);
                    } catch (BusinessException be) {
                        // B5（Q10=A）：个人余额扣不尽 → 差额挂账 DEBT（同聊天结算口径）
                        walletService.chargeToDebt(userId, diff, kind, refId, "媒体结算补扣（余额扣尽差额挂账）");
                        log.warn("媒体结算补扣转挂账 userId={} kind={} refId={} diff={} : {}",
                                userId, kind, refId, diff, be.getMessage());
                    }
                }
            } else if (diff.signum() < 0) {
                // 实耗低于预估 → 退差额
                BigDecimal back = diff.negate();
                if (projectGroupId != null) {
                    groupWalletService.refundGroup(projectGroupId, userId, back, kind,
                            String.valueOf(refId), "media-settle-" + refId);
                } else {
                    walletService.refundIdempotent(userId, back, kind, refId,
                            "媒体任务预估退差（实耗低于预估）", "media-settle-" + refId);
                }
            }
            // 11x P3-C9：扣费成功发媒体滥用事件（按实耗，同 chargeMedia 口径）
            if (securityEventPublisher != null && actual.signum() > 0) {
                securityEventPublisher.publish(
                        com.superprogrammer.common.security.event.ApplicationSecurityEvent.KIND_MEDIA_SUBMIT,
                        userId, java.util.Map.of("estimatedCostFen", actual.longValue(), "taskCount", 1));
            }
            // usage 记实耗（同 chargeMedia 口径，taskId 锚定 drill-down）
            usageCollector.record(userId, providerId, LlmUsageLogEntity.SCOPE_GLOBAL, model, kind,
                    tokensInput, null, yuan, points, status, null, refId, null, projectGroupId);
            return actual;
        } catch (BusinessException e) {
            // 补扣失败（个人差额期间余额耗尽）：实耗已发生，记 FAILED usage 平台可见缺口；
            // 预扣额已确认扣过——返回预扣额供 worker 在 markSucceeded 失败时 unwind
            usageCollector.record(userId, providerId, LlmUsageLogEntity.SCOPE_GLOBAL, model, kind,
                    tokensInput, null, null, null, LlmUsageLogEntity.STATUS_FAILED, e.toString(), refId);
            log.warn("媒体结算补扣失败(已记FAILED,预扣在手) userId={} model={} kind={} refId={} : {}",
                    userId, model, kind, refId, e.toString());
            return heldPoints;
        } catch (Exception e) {
            // 兜底：任何意外都不许回归媒体出口（预扣在手，同 BusinessException 口径返回预扣额）
            log.warn("媒体结算意外异常(吞,预扣在手) userId={} model={} kind={} refId={} : {}",
                    userId, model, kind, refId, e.toString());
            return heldPoints;
        }
    }

    /**
     * markSucceeded 落库失败撤销（7x 预扣版）：两腿退——预扣腿 min(实耗,预扣) 退 kind-HOLD，
     * 补扣腿 (实耗-预扣) 正值部分退 kind；两腿幂等键独立。未预扣 → 原 refundMedia 单腿。吞异常。
     */
    public void refundMediaCharged(Long userId, BigDecimal chargedPoints, BigDecimal heldPoints,
                                   String kind, Long refId, Long projectGroupId) {
        if (chargedPoints == null || chargedPoints.signum() <= 0) {
            return;
        }
        if (heldPoints == null || heldPoints.signum() <= 0) {
            refundMedia(userId, chargedPoints, kind, refId, projectGroupId);
            return;
        }
        BigDecimal holdLeg = chargedPoints.min(heldPoints);
        BigDecimal supLeg = chargedPoints.subtract(heldPoints);
        try {
            if (projectGroupId != null) {
                groupWalletService.refundGroup(projectGroupId, userId, holdLeg, kind + "-HOLD",
                        String.valueOf(refId), "media-hold-refund-" + refId);
                if (supLeg.signum() > 0) {
                    groupWalletService.refundGroup(projectGroupId, userId, supLeg, kind,
                            String.valueOf(refId), "media-settle-refund-" + refId);
                }
            } else {
                walletService.refundIdempotent(userId, holdLeg, kind + "-HOLD", refId,
                        "媒体任务落库失败退预扣", "media-hold-refund-" + refId);
                if (supLeg.signum() > 0) {
                    walletService.refundIdempotent(userId, supLeg, kind, refId,
                            "媒体任务落库失败退补扣", "media-settle-refund-" + refId);
                }
            }
        } catch (Exception e) {
            log.warn("媒体落库失败撤销异常(吞) userId={} kind={} refId={} gid={} : {}",
                    userId, kind, refId, projectGroupId, e.toString());
        }
    }

    /**
     * 任务失败退预扣（7x）：全额退预扣腿 kind-HOLD（组池对称回减成员 used）。
     * 幂等键与落库失败撤销的预扣腿相同——同任务两条失败路径互斥（终态条件 UPDATE 保证只走一条）。吞异常。
     */
    public void refundMediaHold(Long userId, BigDecimal heldPoints, String kind, Long refId,
                                Long projectGroupId) {
        if (heldPoints == null || heldPoints.signum() <= 0 || userId == null) {
            return;
        }
        try {
            if (projectGroupId != null) {
                groupWalletService.refundGroup(projectGroupId, userId, heldPoints, kind + "-HOLD",
                        String.valueOf(refId), "media-hold-refund-" + refId);
            } else {
                walletService.refundIdempotent(userId, heldPoints, kind + "-HOLD", refId,
                        "媒体任务失败退预扣", "media-hold-refund-" + refId);
            }
        } catch (Exception e) {
            log.warn("媒体预扣退款异常(吞) userId={} kind={} refId={} gid={} : {}",
                    userId, kind, refId, projectGroupId, e.toString());
        }
    }

    /**
     * 计费开关透传（worker 第二层 fail-closed 闸用）：结算返回 null 时区分「计费关/系统调用」
     * 与「扣费彻底失败」——后者须退预扣、标 FAILED、不交付产物。
     */
    public boolean billingEnabled() {
        return walletService.isEnabled();
    }

    /**
     * 计划5 Step5：媒体结算兜底——差额扣组长个人（组行 owner），并计入消费成员 used（7x-2）。
     * 组已删/组长钱包不足由 {@code backstop} 自身抛 BusinessException → 外层记 FAILED usage（平台可见缺口）。
     */
    private void backstopMedia(Long groupId, Long consumerUserId, BigDecimal points, String kind, Long refId) {
        var group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new BusinessException(com.superprogrammer.common.exception.ErrorCode.NOT_FOUND,
                    "项目组已删除，无法兜底 groupId=" + groupId);
        }
        groupWalletService.backstop(groupId, group.getOwnerUserId(), consumerUserId, false, points,
                kind, String.valueOf(refId));
    }
}
