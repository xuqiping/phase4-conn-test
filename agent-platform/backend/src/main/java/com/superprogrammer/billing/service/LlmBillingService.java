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
    private final UsageCollector usageCollector;
    /** 审计：对话完成行 chat_completed（8x Chunk4 行2）。 */
    private final AuditLogService auditLogService;
    /** 对话审计开关（audit.chat.enabled）。非 final，Spring @Value 字段注入。 */
    @Value("${audit.chat.enabled:true}")
    private boolean chatAuditEnabled;

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
        if (!walletService.isEnabled()) {
            return null;
        }
        try {
            BigDecimal yuan = pricingService.computeCost(kind, providerId, model,
                    tokensInput, tokensOutput, 0, 0);
            BigDecimal points = ratioService.toPoints(yuan);
            // refType = kind（CHAT/EMBED，与 ledger REF_* 同串）；refId 暂无单次调用 id
            BigDecimal after = walletService.charge(userId, points, kind, null, model);
            usageCollector.record(userId, providerId, providerScope, model, kind,
                    tokensInput, tokensOutput, yuan, points, status, null, null, sessionId);
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
            auditLogService.recordTask("chat", "chat_completed", "chat_session",
                    null, userId, null, null, json, result);
        } catch (Exception e) {
            log.warn("对话完成审计失败(已吞) userId={} model={} : {}", userId, model, e.toString());
        }
    }
}
