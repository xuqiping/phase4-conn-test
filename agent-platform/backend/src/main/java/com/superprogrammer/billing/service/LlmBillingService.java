package com.superprogrammer.billing.service;

import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.common.audit.AuditLogEntity;
import com.superprogrammer.common.audit.AuditLogService;
import com.superprogrammer.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

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
    /** 计划5 Step4：组池计费分支（chargeGroup：组池+成员 used+CONSUME 流水）。 */
    private final com.superprogrammer.projectgroup.service.ProjectGroupWalletService groupWalletService;
    private final UsageCollector usageCollector;
    /** 审计：对话完成行 chat_completed（8x Chunk4 行2）。 */
    private final AuditLogService auditLogService;
    /** 9x#7：流式线程无 MDC/SecurityContext，chat_completed 行的 username 按 userId 反查补齐。 */
    private final com.superprogrammer.auth.mapper.UserMapper userMapper;
    /** 修复IX-1 A4：思考档位 HOLD 估算放大系数（llm.thinking.hold-factor-standard/deep）。 */
    private final com.superprogrammer.llm.config.LlmThinkingProperties thinkingProperties;
    /** 对话审计开关（audit.chat.enabled）。非 final，Spring @Value 字段注入。 */
    @Value("${audit.chat.enabled:true}")
    private boolean chatAuditEnabled;
    /** B3（Q4=B）：聊天预扣开关——关=回退现状（预检>0+答完全量后扣）。 */
    @Value("${billing.chat-hold.enabled:true}")
    private boolean chatHoldEnabled;
    /** B2：字符→token 折算系数（1 token ≈ N 字符，中文经验值 1.6）。 */
    @Value("${billing.chat.char-per-token:1.6}")
    private double charPerToken;
    /**
     * B3：预估输出 token 帽（Q4=B 全额冻结的 est 口径用）——请求 maxTokens 默认 8192，
     * 按它全额冻结会常态性开局拒；est 取 min(maxTokens, 本帽)，超帽实耗走结算多退少补+DEBT 兜底。
     */
    @Value("${billing.chat.hold-est-max-tokens:2048}")
    private int holdEstMaxTokens;

    /**
     * LLM 调用成功：算价→折算→同步扣→异步采。全链吞异常。usage 状态记 SUCCESS。
     *
     * @param kind {@link LlmUsageLogEntity#KIND_CHAT}/EMBED（视频/图片走 Chunk F，不经此 token 路径）
     * @return 扣后余额；未扣（系统调用/disabled/计费失败）返 null
     */
    public BigDecimal onSuccess(Long userId, Long providerId, String providerScope, String model, String kind,
                                Integer tokensInput, Integer tokensOutput) {
        return onSuccess(userId, providerId, providerScope, model, kind, tokensInput, tokensOutput,
                LlmUsageLogEntity.STATUS_SUCCESS);
    }

    /**
     * LLM 调用成功 + 自定 usage 状态（gateway 估算兜底时传 {@link LlmUsageLogEntity#STATUS_ESTIMATED}）。
     * <p>billing.enabled=false（{@link PointsWalletService#isEnabled()}）→ 直接返 null，跳过 computeCost，
     * 避免价表未配时 PRICING_NOT_FOUND 误报噪声（disabled 态 record 也短路）。
     */
    public BigDecimal onSuccess(Long userId, Long providerId, String providerScope, String model, String kind,
                                Integer tokensInput, Integer tokensOutput, String status) {
        return onSuccess(userId, providerId, providerScope, model, kind,
                tokensInput, tokensOutput, status, null);
    }

    /**
     * 安全体系 S3 · SEC-FR-056（LLM10）：+sessionId 会话归户版本。
     * chat 会话出口（gateway chat/chatStream）透传 LlmRequest.sessionId 落 llm_usage_logs.session_id（V122），
     * 供发送前 SUM 封顶检查；其他调用走无 sessionId 重载。
     */
    public BigDecimal onSuccess(Long userId, Long providerId, String providerScope, String model, String kind,
                                Integer tokensInput, Integer tokensOutput, String status, String sessionId) {
        return onSuccess(userId, providerId, providerScope, model, kind,
                tokensInput, tokensOutput, status, sessionId, null);
    }

    /**
     * 计划5 Step4：+projectGroupId 组池计费版本（网关入口已过 requireAffordableGroup 成员/余额预检）。
     * gid 非空且 uid 非空 → chargeGroup（组池+成员 used+CONSUME 流水，错误按铁律吞不回归出口）；
     * 否则个人 charge（现状）。usage 落 project_group_id（账单事实源）。
     */
    public BigDecimal onSuccess(Long userId, Long providerId, String providerScope, String model, String kind,
                                Integer tokensInput, Integer tokensOutput, String status, String sessionId,
                                Long projectGroupId) {
        return onSuccess(userId, providerId, providerScope, model, kind,
                tokensInput, tokensOutput, status, sessionId, projectGroupId, null);
    }

    /**
     * 9x-1（V160 D5）：+cachedTokens 全参版本——缓存命中腿进计价 + 落 usage 列。
     * 仅 CHAT 有意义（textCost 对 EMBED/RERANK 强制 null）；估算兜底路径传 null（退化两腿）。
     */
    public BigDecimal onSuccess(Long userId, Long providerId, String providerScope, String model, String kind,
                                Integer tokensInput, Integer tokensOutput, String status, String sessionId,
                                Long projectGroupId, Long cachedTokens) {
        if (!walletService.isEnabled()) {
            return null;
        }
        try {
            BigDecimal yuan = pricingService.computeCost(kind, providerId, model,
                    tokensInput, tokensOutput, 0, 0, false, null, cachedTokens);
            BigDecimal points = ratioService.toPoints(yuan);
            BigDecimal after;
            if (projectGroupId != null && userId != null) {
                // 组池：成员身份入口已验；V161 allowDebt=true——真实消耗走瀑布（池→名下→组长兜底），
                // 溢出转成员欠款；仅系统级错误按铁律吞→FAILED usage
                after = groupWalletService.chargeGroup(projectGroupId, userId, points, kind, model, null, true);
            } else {
                // refType = kind（CHAT/EMBED，与 ledger REF_* 同串）；refId 暂无单次调用 id
                after = walletService.charge(userId, points, kind, null, model);
            }
            usageCollector.record(userId, providerId, providerScope, model, kind,
                    tokensInput, tokensOutput, yuan, points, status, null, null, sessionId,
                    projectGroupId, cachedTokens);
            // 8x Chunk4 行2：对话完成审计（单一计算源——复用本帧 tokens/points，不二次算价，坑点 #11）
            auditChatCompleted(userId, model, kind, tokensInput, tokensOutput, points,
                    AuditLogEntity.RESULT_SUCCESS, null);
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

    // ==================== B2/B3：聊天 HOLD 预扣 + 多退少补 + 取消折算（Q2/Q3/Q4=B，镜像媒体 V155） ====================

    /** 预扣开关透传（网关入口判断用）。 */
    public boolean isChatHoldEnabled() {
        return chatHoldEnabled && walletService.isEnabled();
    }

    /**
     * B3（Q4=B）：聊天开局全额预扣。est = prompt 估算 tokens×入价 + min(maxTokens, est帽)×出价；
     * 可用（个人余额/组池余额）&lt; est → 抛 INSUFFICIENT_POINTS（带两数，B4 SSE 话术通路）。
     * <b>不吞异常</b>——开局拦截是本方法存在的目的（区别于结算腿的铁律吞异常）。
     *
     * @param ref 稳定调用锚（chat 会话=用户消息 id；无锚调用途径传唯一串）——幂等键 chat-hold-{ref}
     * @return 预扣额（开关关/系统调用/估价≤0 → null=未预扣，后续走答完后扣现状）
     */
    public BigDecimal holdChat(Long userId, Long projectGroupId, Long providerId, String model,
                               int estInputTokens, Integer requestMaxTokens, String ref) {
        return holdChat(userId, projectGroupId, providerId, model, estInputTokens, requestMaxTokens, ref, null);
    }

    /**
     * 修复IX-1 A4（Q2 拍板）：带思考档位的预扣——深度思考产出更长，est 输出按档位放大系数抬估算
     * （STANDARD ×hold-factor-standard / DEEP ×hold-factor-deep，默认 2/4），防思考态开局冻结不足
     * 常态转 DEBT。null/OFF=现状口径不动。est 帽仍生效（放大后可与 maxTokens 比较，超帽截断）。
     */
    public BigDecimal holdChat(Long userId, Long projectGroupId, Long providerId, String model,
                               int estInputTokens, Integer requestMaxTokens, String ref,
                               com.superprogrammer.llm.dto.ThinkingLevel thinkingLevel) {
        if (!isChatHoldEnabled() || userId == null) {
            return null;
        }
        int base = requestMaxTokens == null ? holdEstMaxTokens : Math.min(requestMaxTokens, holdEstMaxTokens);
        int scaled = base;
        if (thinkingLevel == com.superprogrammer.llm.dto.ThinkingLevel.STANDARD) {
            scaled = base * thinkingProperties.getHoldFactorStandard();
        } else if (thinkingLevel == com.superprogrammer.llm.dto.ThinkingLevel.DEEP) {
            scaled = base * thinkingProperties.getHoldFactorDeep();
        }
        int estOut = Math.min(scaled, requestMaxTokens == null ? Integer.MAX_VALUE : Math.max(requestMaxTokens, 1));
        BigDecimal yuan = pricingService.computeCost(LlmUsageLogEntity.KIND_CHAT, providerId, model,
                estInputTokens, estOut, 0, 0);
        BigDecimal est = ratioService.toPoints(yuan);
        if (est == null || est.signum() <= 0) {
            return null;
        }
        BigDecimal available = projectGroupId != null
                ? groupWalletService.getGroupBalance(projectGroupId)
                : walletService.getBalance(userId);
        if (available == null) {
            available = BigDecimal.ZERO;
        }
        if (available.compareTo(est) < 0) {
            throw new BusinessException(com.superprogrammer.common.exception.ErrorCode.INSUFFICIENT_POINTS,
                    "积分不足：本次预估上限 " + est.stripTrailingZeros().toPlainString()
                            + "（上下文+最大输出），当前可用 " + available.stripTrailingZeros().toPlainString()
                            + "，请先充值或调小 max_tokens");
        }
        if (projectGroupId != null) {
            groupWalletService.chargeGroup(projectGroupId, userId, est, "CHAT-HOLD", ref, "chat-hold-" + ref, false);
        } else {
            walletService.chargeIdempotent(userId, est, "CHAT-HOLD", null,
                    "聊天预扣（答完按实际用量多退少补）", "chat-hold-" + ref);
        }
        log.info("聊天预扣 userId={} gid={} ref={} est={}", userId, projectGroupId, ref, est);
        return est;
    }

    /**
     * B3 正常尾结算：usage 精确实耗 vs 预扣 多退少补（补扣 CONSUME(CHAT) / 退差 REFUND(CHAT)，幂等键 chat-settle-{ref}）。
     * V161：组模式补差走瀑布（池→名下→组长兜底）扣到底；个人补扣不足 → chargeToDebt 挂账（B5/Q10=A）。
     * usage 采集与 chat_completed 审计与 onSuccess 同口径。吞异常（结算旁路铁律）。
     *
     * @return 实耗积分（结算失败返回预扣额——预扣在手不重复 unwind）
     */
    public BigDecimal settleChatHeld(Long userId, Long providerId, String providerScope, String model,
                                     Integer tokensInput, Integer tokensOutput, String status, String sessionId,
                                     Long projectGroupId, String ref, BigDecimal heldPoints) {
        return settleChatHeld(userId, providerId, providerScope, model,
                tokensInput, tokensOutput, status, sessionId, projectGroupId, ref, heldPoints, null);
    }

    /**
     * 9x-1（V160 D5）：+cachedTokens 全参版本——结算价含缓存腿（缓存价 NULL 时与原结算逐分一致）。
     * hold 侧不传缓存（预扣时命中不可预知，按未命中保守估——见 holdChat）；尾结算用真实命中修正。
     */
    public BigDecimal settleChatHeld(Long userId, Long providerId, String providerScope, String model,
                                     Integer tokensInput, Integer tokensOutput, String status, String sessionId,
                                     Long projectGroupId, String ref, BigDecimal heldPoints, Long cachedTokens) {
        try {
            BigDecimal yuan = pricingService.computeCost(LlmUsageLogEntity.KIND_CHAT, providerId, model,
                    tokensInput, tokensOutput, 0, 0, false, null, cachedTokens);
            BigDecimal actual = yuan == null ? BigDecimal.ZERO : ratioService.toPoints(yuan);
            settleDiff(userId, projectGroupId, model, ref, actual.subtract(heldPoints));
            usageCollector.record(userId, providerId, providerScope, model, LlmUsageLogEntity.KIND_CHAT,
                    tokensInput, tokensOutput, yuan, actual, status, null, null, sessionId,
                    projectGroupId, cachedTokens);
            auditChatCompleted(userId, model, LlmUsageLogEntity.KIND_CHAT, tokensInput, tokensOutput,
                    actual, AuditLogEntity.RESULT_SUCCESS, null);
            return actual;
        } catch (BusinessException e) {
            usageCollector.record(userId, providerId, providerScope, model, LlmUsageLogEntity.KIND_CHAT,
                    tokensInput, tokensOutput, null, null, LlmUsageLogEntity.STATUS_FAILED, e.getMessage());
            log.warn("聊天结算补扣失败(已记FAILED,预扣在手) userId={} model={} ref={} : {}",
                    userId, model, ref, e.toString());
            return heldPoints;
        } catch (Exception e) {
            log.warn("聊天结算意外异常(吞,预扣在手) userId={} model={} ref={} : {}", userId, model, ref, e.toString());
            return heldPoints;
        }
    }

    /**
     * B3 取消/中断折算结算（Q3=B）：provider 无 usage（用户停止/流错/完成但未回 usage）时按已产字符折算：
     * tokens = chars÷系数，实耗 = min(折算积分, 预扣)，差额退（REFUND CHAT-HOLD，幂等键 chat-cancel-{ref}）；
     * 折算=0（一字未产）全额退。usage 记 ESTIMATED。吞异常。
     */
    public void settleChatCancelled(Long userId, Long providerId, String providerScope, String model,
                                    Long projectGroupId, String ref, BigDecimal heldPoints, long producedChars,
                                    String sessionId) {
        if (heldPoints == null || heldPoints.signum() <= 0) {
            return; // 未预扣（开关关/答完后扣现状）→ 取消时本就没扣
        }
        try {
            long tokensOut = charPerToken <= 0 ? 0 : Math.round(producedChars / charPerToken);
            BigDecimal actual = BigDecimal.ZERO;
            if (tokensOut > 0) {
                BigDecimal yuan = pricingService.computeCost(LlmUsageLogEntity.KIND_CHAT, providerId, model,
                        null, (int) Math.min(tokensOut, Integer.MAX_VALUE), 0, 0);
                BigDecimal est = yuan == null ? null : ratioService.toPoints(yuan);
                if (est != null && est.signum() > 0) {
                    actual = est.min(heldPoints);
                }
            }
            BigDecimal back = heldPoints.subtract(actual);
            if (back.signum() > 0) {
                if (projectGroupId != null) {
                    groupWalletService.refundGroup(projectGroupId, userId, back, "CHAT-HOLD", ref,
                            "chat-cancel-" + ref);
                } else {
                    walletService.refundIdempotent(userId, back, "CHAT-HOLD", null,
                            "聊天中止退差（按已产内容折算 " + actual.stripTrailingZeros().toPlainString() + "）",
                            "chat-cancel-" + ref);
                }
            }
            if (actual.signum() > 0 || producedChars > 0) {
                usageCollector.record(userId, providerId, providerScope, model, LlmUsageLogEntity.KIND_CHAT,
                        null, (int) Math.min(tokensOut, Integer.MAX_VALUE), null, actual,
                        LlmUsageLogEntity.STATUS_ESTIMATED, "cancelled", null, sessionId, projectGroupId);
            }
            log.info("聊天中止折算结算 userId={} gid={} ref={} chars={} 实扣={} 退={}",
                    userId, projectGroupId, ref, producedChars, actual, back.max(BigDecimal.ZERO));
        } catch (Exception e) {
            log.warn("聊天中止结算异常(吞) userId={} ref={} : {}", userId, ref, e.toString());
        }
    }

    /** B2：PROGRESS 折算——已产字符→估算积分（网关流中报数用；估价失败返 null 不发事件）。 */
    public BigDecimal estimateCharsPoints(Long providerId, String model, long chars) {
        try {
            long tokens = charPerToken <= 0 ? 0 : Math.round(chars / charPerToken);
            if (tokens <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal yuan = pricingService.computeCost(LlmUsageLogEntity.KIND_CHAT, providerId, model,
                    null, (int) Math.min(tokens, Integer.MAX_VALUE), 0, 0);
            return yuan == null ? null : ratioService.toPoints(yuan);
        } catch (Exception e) {
            return null;
        }
    }

    /** B2：字符→token 折算透传（PROGRESS 事件的 estimatedTokens 字段用，与折算结算同系数）。 */
    public long foldCharsToTokens(long chars) {
        return charPerToken <= 0 ? 0 : Math.round(chars / charPerToken);
    }

    /** 结算差额腿：正=补扣（V161 瀑布 池→名下→组长兜底，allowDebt=true）、负=退差。幂等键 chat-settle-{ref}。 */
    private void settleDiff(Long userId, Long projectGroupId, String model, String ref, BigDecimal diff) {
        if (diff.signum() == 0) {
            return;
        }
        if (diff.signum() > 0) {
            if (projectGroupId != null) {
                // V161 修复III 瀑布：补差扣到底（池→名下→组长兜底），超限额溢出转成员欠款，
                // 不再「限额守卫失败整单回滚→组长全额垫」（缺陷1根除）。系统级错误上抛由调用侧铁律吞
                groupWalletService.chargeGroup(projectGroupId, userId, diff, "CHAT", ref, "chat-settle-" + ref, true);
            } else {
                try {
                    walletService.chargeIdempotent(userId, diff, "CHAT", null,
                            "聊天结算补扣（实耗超预估）", "chat-settle-" + ref);
                } catch (BusinessException be) {
                    // B5（Q10=A）：个人余额扣不尽 → 差额挂账 DEBT（开关关时 chargeToDebt 自空转=平台担损现状）
                    walletService.chargeToDebt(userId, diff, "CHAT", null, "聊天结算补扣（余额扣尽差额挂账）");
                    log.warn("聊天结算补扣转挂账 userId={} diff={} : {}", userId, diff, be.getMessage());
                }
            }
        } else {
            BigDecimal back = diff.negate();
            if (projectGroupId != null) {
                groupWalletService.refundGroup(projectGroupId, userId, back, "CHAT", ref, "chat-settle-" + ref);
            } else {
                walletService.refundIdempotent(userId, back, "CHAT", null,
                        "聊天结算退差（实耗低于预估）", "chat-settle-" + ref);
            }
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
            // 8x Chunk4 行2 失败分支：模型调用失败也记 chat_completed(FAIL)
            auditChatCompleted(userId, model, kind, null, null, null,
                    AuditLogEntity.RESULT_FAIL, errorMsg);
        } catch (Exception e) {
            log.warn("失败 usage 采集异常(吞) : {}", e.toString());
        }
    }

    /**
     * 8x Chunk4 行2：对话完成审计（chat_completed）。detail 带 model/kind/tokens/积分——
     * <b>tokens 与 points 复用本帧计费已算值（单一计算源，坑点 #11），禁二次算价</b>。
     *
     * <p>关联键：userId 显式传；traceId/clientIp/username 取 MDC（同步 chat 路径有；流式 reactor 线程暂空，
     * Chunk7 启用 context-propagation 后自动补全，本处前向兼容无需改）。sessionId 不在本服务作用域，
     * targetId 留 null（靠 traceId 串 send_message↔chat_completed↔llm_usage_logs，Chunk7 落地）。
     * 仅 kind=CHAT 且 userId 非空（系统 embed 调用不记）且开关开时落。失败一律吞（计费旁路铁律）。
     */
    private void auditChatCompleted(Long userId, String model, String kind, Integer tokensInput,
                                    Integer tokensOutput, BigDecimal pointsConsumed, String result, String reason) {
        if (!chatAuditEnabled || !LlmUsageLogEntity.KIND_CHAT.equals(kind) || userId == null) {
            return;
        }
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("model", model);
            detail.put("kind", "CHAT");
            if (tokensInput != null) {
                detail.put("tokensInput", tokensInput);
            }
            if (tokensOutput != null) {
                detail.put("tokensOutput", tokensOutput);
            }
            if (pointsConsumed != null) {
                detail.put("pointsConsumed", pointsConsumed);
            }
            if (reason != null) {
                detail.put("reason", reason.length() > 200 ? reason.substring(0, 200) : reason);
            }
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(detail);
            // 9x#7：流式 raw 线程无 MDC，recordTask 的 username 不能靠 MdcUserFilter——按 userId 反查（一次一条，低频）。
            String username = null;
            try {
                com.superprogrammer.auth.entity.User u = userMapper.selectById(userId);
                username = u == null ? null : u.getUsername();
            } catch (Exception ignore) { /* 反查失败不挡审计落库 */ }
            auditLogService.recordTask("chat", "chat_completed", "chat_session",
                    null, userId, username, null, json, result);
        } catch (Exception e) {
            log.warn("对话完成审计失败(已吞) userId={} model={} : {}", userId, model, e.toString());
        }
    }
}
