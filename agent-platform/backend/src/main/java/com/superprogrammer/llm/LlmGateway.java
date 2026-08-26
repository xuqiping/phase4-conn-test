package com.superprogrammer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.billing.context.BillingContext;
import com.superprogrammer.billing.entity.LlmUsageLogEntity;
import com.superprogrammer.billing.service.InflightGateService;
import com.superprogrammer.billing.service.LlmBillingService;
import com.superprogrammer.billing.service.PointsWalletService;
import com.superprogrammer.chat.dto.StreamEvent;
import com.superprogrammer.common.metrics.BizMetrics;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import com.superprogrammer.llm.config.LlmConfig;
import com.superprogrammer.llm.dto.EmbedResult;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import com.superprogrammer.llm.dto.TokenUsage;
import com.superprogrammer.llm.dto.RerankRequest;
import com.superprogrammer.llm.dto.RerankResult;
import com.superprogrammer.llm.entity.LlmProviderEntity;
import com.superprogrammer.llm.entity.UserLlmProviderEntity;
import com.superprogrammer.llm.provider.ClaudeProvider;
import com.superprogrammer.llm.provider.LlmProviderInterface;
import com.superprogrammer.llm.provider.OpenAICompatibleProvider;
import com.superprogrammer.llm.service.LlmProviderService;
import com.superprogrammer.llm.service.UserLlmProviderService;
import com.superprogrammer.knowledge.util.TokenEstimator;
import com.superprogrammer.knowledge.trace.RagTraceService;
import com.superprogrammer.system.service.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmGateway {

    private final LlmConfig llmConfig;
    private final UserLlmProviderService userLlmProviderService;
    private final LlmProviderService llmProviderService;
    private final ObjectMapper objectMapper;
    /** 计费编排（算价→折算→扣→采，全链吞异常不回归出口）。 */
    private final LlmBillingService billingService;
    /** 钱包：入口预检 requireAffordable（≤0 抛 INSUFFICIENT_POINTS）。 */
    private final PointsWalletService walletService;
    /** 计划5 Step4：组池预检（非成员 403/组池尽 40201）。 */
    private final com.superprogrammer.projectgroup.service.ProjectGroupWalletService groupWalletService;
    /** 运维系统 OPS-FR-03：LLM 指标统一出口埋点（calls/tokens/latency，tag 仅 provider/model/result/direction）。 */
    private final BizMetrics bizMetrics;
    /** 安全体系 S2 · L7 低余额并行闸门（SEC-FR-126）：只挂 chat/chatStream 用户入口；embed 与系统调用不过闸。 */
    private final InflightGateService inflightGate;
    /** 模型为空时只读管理员默认；没有默认则明确报错，绝不硬编码厂商模型。 */
    private final SystemSettingService systemSettingService;
    private final RagTraceService ragTraceService;

    /**
     * 安全体系 S3 · SEC-FR-052/053（LLM02/07②）：输出净化（敏感打码 + prompt 泄露指纹遮蔽）。
     * 横切可选依赖（沉淀范式）：测试/切片无 bean 时降级直通；sanitize 内部异常也透传原文。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.superprogrammer.common.security.ai.OutputSanitizer outputSanitizer;

    public LlmResponse chat(LlmRequest request) {
        // 无 userId（系统调用）→ userId=null：仅采不扣（charge 在 userId=null 时短路）。
        return chat(request, null);
    }

    public LlmResponse chat(LlmRequest request, Long userId) {
        resolveChatModel(request);
        Long uid = resolveBillingUser(userId, request.getModel());
        // 计划5 Step4：显式 gid 优先，空回退 BillingContext（KB ask 链路内部调用免签名透传）
        Long gid = resolveBillingGid(request.getProjectGroupId());
        LlmProviderInterface provider = findProvider(request.getModel(), uid);
        log.info("LLM调用 model={} provider={} userId={}", request.getModel(), provider.getName(), uid);
        // 入口预检：余额≤0 抛 INSUFFICIENT_POINTS（disabled/系统调用自短路）。在 try 外，未调用不记 FAILED。
        // 余额复用：返回值直接喂给闸门，省一次重复查库
        // 计划5 Step4：带 projectGroupId → 组池预检（非成员 403/组池尽 40201），组池计费
        java.math.BigDecimal balance = requireAffordableFor(uid, gid, LlmUsageLogEntity.KIND_CHAT);
        // B3（Q4=B）：开局全额预扣（est=上下文估算+min(maxTokens,2048) 出量）。开关关/系统调用返 null 走现状。
        // 抛 INSUFFICIENT_POINTS=开局拦截（此时闸门未 acquire、provider 未调，直接外抛，B4/SSE 上层分流）。
        String holdRef = "chat-" + java.util.UUID.randomUUID();
        java.math.BigDecimal heldPoints = billingService.holdChat(uid, gid, provider.getId(),
                request.getModel(), estimateInputTokens(request), request.getMaxTokens(), holdRef);
        // L7 低余额并行闸门：低余额用户超在途上限在此抛 42902；held=true 须 finally release
        boolean held = inflightGate.acquire(uid, balance);
        long startNanos = System.nanoTime();
        var ragCall = ragTraceService.beginModelCall(request.getModel(), provider.getName(),
                summarizeInput(request), firstText(request.getCallPurpose(), "ANSWER_GENERATION"));
        try (ragCall) {
            LlmResponse response = provider.chat(request);
            TokenUsage usage = response.getUsage();
            Integer in = usage != null ? usage.getPromptTokens() : null;
            Integer out = usage != null ? usage.getCompletionTokens() : null;
            String status = LlmUsageLogEntity.STATUS_SUCCESS;
            // 估算兜底：parseResponse 理论总带 usage，防御 provider 不回时用 message 内容估算 input
            if ((in == null || in == 0) && (out == null || out == 0)) {
                in = estimateInputTokens(request);
                out = 0;
                status = LlmUsageLogEntity.STATUS_ESTIMATED;
            }
            if (heldPoints != null) {
                // B3：预扣在手 → 精确 usage 多退少补（结算失败吞异常，预扣不重复 unwind）
                // 9x-1（V160 D5）：缓存腿随真实 usage 进结算（估算兜底分支 usage=null → cached null 两腿）
                billingService.settleChatHeld(uid, provider.getId(), provider.getProviderScope(),
                        request.getModel(), in, out, status, request.getSessionId(), gid,
                        holdRef, heldPoints, usage != null ? usage.getCachedTokens() : null);
            } else {
                billingService.onSuccess(uid, provider.getId(), provider.getProviderScope(),
                        request.getModel(), LlmUsageLogEntity.KIND_CHAT, in, out, status,
                        request.getSessionId(), gid, usage != null ? usage.getCachedTokens() : null);
            }
            recordLlmSuccess(provider.getName(), request.getModel(), in, out, startNanos);
            ragCall.succeed(response.getContent(), in, out);
            // 安全体系 S3：出口净化（null bean/异常均透传原文，见 OutputSanitizer）
            response.setContent(sanitizeSync(response.getContent(), uid));
            return response;
        } catch (RuntimeException e) {
            ragCall.fail(e.getMessage());
            if (heldPoints != null) {
                // B3：provider 失败一字未产 → 全额退预扣（chars=0 走取消折算路径）
                billingService.settleChatCancelled(uid, provider.getId(), provider.getProviderScope(),
                        request.getModel(), gid, holdRef, heldPoints, 0L, request.getSessionId());
            }
            billingService.onFailure(uid, provider.getId(), provider.getProviderScope(),
                    request.getModel(), LlmUsageLogEntity.KIND_CHAT, e.getMessage());
            recordLlmTerminal(provider.getName(), request.getModel(), BizMetrics.RESULT_FAIL, startNanos);
            throw e;
        } finally {
            if (held) {
                inflightGate.release(uid);
            }
        }
    }

    public Flux<StreamEvent> chatStream(LlmRequest request) {
        // 无 userId（系统调用）→ userId=null：仅采不扣。
        return chatStream(request, null);
    }

    /**
     * 流式出口计费（Step9/10 side-channel + Step12 接线）：
     * <ul>
     *   <li>入口预检 requireAffordable（≤0 拦）；</li>
     *   <li>usage 经 provider 的 side-channel sink 在 {@code doOnComplete} 回灌 → 在此采+扣；</li>
     *   <li>{@code publishOn(boundedElastic)} 把完成信号切到阻塞容忍调度器，扣减 DB 同步事务不阻塞 netty
     *       event-loop（不改发出的 StreamEvent 序列，仅线程切换，SSE 字节不变）；</li>
     *   <li>provider 不回 usage（sink 不触发）→ 不采不扣（流式无可靠 output，宁少收不误收）。</li>
     * </ul>
     */
    public Flux<StreamEvent> chatStream(LlmRequest request, Long userId) {
        resolveChatModel(request);
        Long uid = resolveBillingUser(userId, request.getModel());
        // 计划5 Step4：显式 gid 优先，空回退 BillingContext（组装期解析一次，流内 usage sink 复用）
        Long gid = resolveBillingGid(request.getProjectGroupId());
        LlmProviderInterface provider = findProvider(request.getModel(), uid);
        log.info("LLM流式调用 model={} provider={} userId={}", request.getModel(), provider.getName(), uid);
        java.math.BigDecimal balance = requireAffordableFor(uid, gid, LlmUsageLogEntity.KIND_CHAT);
        Long providerId = provider.getId();
        String providerScope = provider.getProviderScope();
        String providerName = provider.getName();
        String model = request.getModel();
        long startNanos = System.nanoTime();
        // OPS-FR-03 流式正好一次：doOnComplete/doOnError/doOnCancel 三分支互斥，各记一次终态；
        // tokens 在 usage sink 回灌时记（provider 不回 usage → 不记，与计费同口径宁少不误）。
        //
        // L7 槽位生命周期=订阅生命周期：acquire 推迟到 Flux.defer 订阅期（组装期 acquire 的话
        // 「Flux 从未被订阅」与「provider.chatStream 组装期抛异常」两条路径都会泄漏槽位）；
        // 订阅后终结三态互斥必走 doFinally，acquire/release 正好一次配对。
        return Flux.defer(() -> {
            boolean held = inflightGate.acquire(uid, balance);
            RagTraceService.ModelCallScope ragCall = ragTraceService.beginModelCall(model, providerName,
                    summarizeInput(request), firstText(request.getCallPurpose(), "ANSWER_GENERATION_STREAM"));
            // Flux 生命周期可能跨 Reactor 线程；不能把创建线程的 ThreadLocal/MDC scope 长期挂到流结束。
            // 立即恢复订阅线程，后续每个 usage/terminal 回调按快照短暂恢复同一 RAG Trace。
            ragCall.detach();
            java.util.concurrent.atomic.AtomicReference<TokenUsage> ragUsage = new java.util.concurrent.atomic.AtomicReference<>();
            // B2/B3（Q2/Q3/Q4=B）：已产字符（净化后口径=用户实见）、PROGRESS 节流序号/时间戳、
            // 预扣结算守卫（usage sink 结算成功后，终态取消折算不再跑——钱已按精确实耗收讫）。
            java.util.concurrent.atomic.AtomicLong producedChars = new java.util.concurrent.atomic.AtomicLong();
            java.util.concurrent.atomic.AtomicLong progressSeq = new java.util.concurrent.atomic.AtomicLong();
            java.util.concurrent.atomic.AtomicLong lastProgressAt = new java.util.concurrent.atomic.AtomicLong();
            java.util.concurrent.atomic.AtomicBoolean usageSettled = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<java.math.BigDecimal> settledPoints =
                    new java.util.concurrent.atomic.AtomicReference<>();
            // 安全体系 S3：每订阅一个流式净化器（carry 状态不可跨流复用）；null bean → 直通
            final com.superprogrammer.common.security.ai.OutputSanitizer.StreamMasker masker =
                    outputSanitizer == null ? null : outputSanitizer.openStream(uid);
            final Flux<StreamEvent> inner;
            // B3：稳定调用锚（结算/退款幂等键挂它）
            final String holdRef = "cs-" + java.util.UUID.randomUUID();
            try {
                // B3（Q4=B）：开局全额预扣。抛 INSUFFICIENT_POINTS → 下方 catch 释放闸门槽位后
                // 转 Flux.error（此刻未产任何 chunk，B4 按「未开局」分流回 ERROR 事件）。
                final java.math.BigDecimal holdPoints = billingService.holdChat(uid, gid, providerId, model,
                        estimateInputTokens(request), request.getMaxTokens(), holdRef);
                final Runnable cancelSettle = () -> {
                    if (holdPoints != null && !usageSettled.get()) {
                        billingService.settleChatCancelled(uid, providerId, providerScope, model, gid,
                                holdRef, holdPoints, producedChars.get(), request.getSessionId());
                    }
                };
                inner = provider.chatStream(request, usage -> {
                            ragCall.runWithContext(() -> {
                                ragUsage.set(usage);
                                if (holdPoints != null) {
                                    // B3：预扣在手 → 精确 usage 多退少补；返回实耗积分给 USAGE 事件展示
                                    // 9x-1（V160 D5）：缓存腿随真实 usage 进结算
                                    settledPoints.set(billingService.settleChatHeld(uid, providerId,
                                            providerScope, model,
                                            usage.getPromptTokens(), usage.getCompletionTokens(),
                                            LlmUsageLogEntity.STATUS_SUCCESS, request.getSessionId(),
                                            gid, holdRef, holdPoints, usage.getCachedTokens()));
                                    usageSettled.set(true);
                                } else {
                                    billingService.onSuccess(uid, providerId, providerScope,
                                            model, LlmUsageLogEntity.KIND_CHAT,
                                            usage.getPromptTokens(), usage.getCompletionTokens(),
                                            LlmUsageLogEntity.STATUS_SUCCESS, request.getSessionId(),
                                            gid, usage.getCachedTokens());
                                }
                                bizMetrics.llmTokens(providerName, model, BizMetrics.DIRECTION_IN,
                                        usage.getPromptTokens() == null ? 0 : usage.getPromptTokens());
                                bizMetrics.llmTokens(providerName, model, BizMetrics.DIRECTION_OUT,
                                        usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
                            });
                        })
                        .publishOn(Schedulers.boundedElastic())
                        .doOnComplete(() -> {
                            ragCall.runWithContext(() -> {
                                recordLlmTerminal(providerName, model, BizMetrics.RESULT_SUCCESS, startNanos);
                                TokenUsage usage = ragUsage.get();
                                ragCall.succeed(null, usage == null ? null : usage.getPromptTokens(),
                                        usage == null ? null : usage.getCompletionTokens());
                            });
                            // B3：正常完流但 provider 未回 usage → 按已产字符折算结算
                            cancelSettle.run();
                        })
                        .doOnError(e -> {
                            ragCall.runWithContext(() -> {
                                recordLlmTerminal(providerName, model, BizMetrics.RESULT_FAIL, startNanos);
                                ragCall.fail(e.getMessage());
                            });
                            cancelSettle.run();
                        })
                        .doOnCancel(() -> {
                            ragCall.runWithContext(() -> {
                                recordLlmTerminal(providerName, model, BizMetrics.RESULT_CANCEL, startNanos);
                                ragCall.cancel();
                            });
                            cancelSettle.run();
                        })
                        // 安全体系 S3：CHUNK/THINKING 过净化器（40 字符 carry）；终态事件前补发尾段
                        .concatMap(evt -> sanitizeStreamEvent(evt, masker))
                        // B2（Q2=B）：流中实时报数——净化后 CHUNK 累计字符，每 32 chunk 或 ≥1s 发一条
                        // PROGRESS（chars/估算 tokens/估算积分，前端计数跳动，DONE.usage 到达后以精确值为准）。
                        .concatMap(evt -> {
                            if (!"CHUNK".equals(evt.getType()) || evt.getContent() == null) {
                                return Flux.just(evt);
                            }
                            long chars = producedChars.addAndGet(evt.getContent().length());
                            long seq = progressSeq.incrementAndGet();
                            long now = System.nanoTime();
                            long last = lastProgressAt.get();
                            if ((seq & 31L) != 0 && now - last < 1_000_000_000L) {
                                return Flux.just(evt);
                            }
                            if (!lastProgressAt.compareAndSet(last, now)) {
                                return Flux.just(evt);
                            }
                            java.math.BigDecimal pts = billingService.estimateCharsPoints(providerId, model, chars);
                            return pts == null ? Flux.just(evt)
                                    : Flux.just(evt, StreamEvent.progress(chars,
                                            billingService.foldCharsToTokens(chars), pts));
                        })
                        // 2026-08-17 实测④续：chat 流的 DONE 由服务层在网关流之后追加——净化器靠
                        // 「非 CHUNK 事件」触发的 flush 分支永不执行，末 ≤40 字符扣留段（含
                        // max_tokens 截断标记 chunk）被整体吞掉。此处流终结统一补发扣留尾段。
                        // B2（Q2=B）：尾随 USAGE 内部事件（provider 精确 usage + B3 结算实耗积分），
                        // ChatSessionService 捕获后并入 DONE.data.usage，不透前端。
                        .concatWith(Flux.defer(() -> {
                            String rest = masker == null ? "" : masker.flush();
                            TokenUsage usage = ragUsage.get();
                            java.util.List<StreamEvent> tail = new java.util.ArrayList<>(2);
                            if (!rest.isEmpty()) {
                                tail.add(StreamEvent.chunk(rest));
                            }
                            if (usage != null) {
                                tail.add(StreamEvent.usage(
                                        usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                                        usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens(),
                                        usage.getCachedTokens(),
                                        settledPoints.get()));
                            }
                            return tail.isEmpty() ? Flux.empty() : Flux.fromIterable(tail);
                        }));
            } catch (RuntimeException e) {
                // 组装期抛异常 → doFinally 尚未注册，此处配对释放
                if (held) {
                    inflightGate.release(uid);
                }
                ragCall.runWithContext(() -> ragCall.fail(e.getMessage()));
                ragCall.close();
                throw e;
            }
            return inner.doFinally(signal -> {
                if (held) {
                    inflightGate.release(uid);
                }
                ragCall.close();
            });
        });
    }

    public float[] embed(String text, String model) {
        return embed(text, model, null);
    }

    /**
     * embed 出口计费（Step11 embedWithUsage + Step12 接线）：
     * 入口预检 → provider.embedWithUsage → usage 非空记 SUCCESS，否则 TokenEstimator 估算 input 记 ESTIMATED。
     * <p>embed 路由仍在全局 EMBEDDING 行找（FR-003，不吃用户级 override）；userId 仅透给计费。
     */
    public float[] embed(String text, String model, Long userId) {
        return embed(text, model, userId, null);
    }

    /** 计划5 Step4：+projectGroupId 组池计费版本（KB ask 链路贯穿）。显式 gid 空 → 回退 BillingContext。 */
    public float[] embed(String text, String model, Long userId, Long projectGroupId) {
        model = resolveEmbeddingModel(model);
        Long uid = resolveBillingUser(userId, model);
        Long gid = resolveBillingGid(projectGroupId);
        LlmProviderInterface provider = findEmbedProvider(model);
        log.info("embedding 调用 model={} provider={} userId={}", model, provider.getName(), uid);
        requireAffordableFor(uid, gid, LlmUsageLogEntity.KIND_EMBED);
        long startNanos = System.nanoTime();
        var ragCall = ragTraceService.beginModelCall(model, provider.getName(), text, "QUERY_EMBEDDING");
        try (ragCall) {
            EmbedResult res = provider.embedWithUsage(text, model);
            TokenUsage usage = res.getUsage();
            Integer in;
            Integer out;
            String status;
            if (usage != null) {
                in = usage.getPromptTokens();
                out = usage.getCompletionTokens();
                status = LlmUsageLogEntity.STATUS_SUCCESS;
            } else {
                // 估算兜底（chars/4）：embed 多无 output，input 用文本估算
                in = TokenEstimator.estimate(text);
                out = 0;
                status = LlmUsageLogEntity.STATUS_ESTIMATED;
            }
            billingService.onSuccess(uid, provider.getId(), provider.getProviderScope(),
                    model, LlmUsageLogEntity.KIND_EMBED, in, out, status, null, gid);
            recordLlmSuccess(provider.getName(), model, in, out, startNanos);
            ragCall.succeed(null, in, out);
            return res.getEmbedding();
        } catch (RuntimeException e) {
            ragCall.fail(e.getMessage());
            billingService.onFailure(uid, provider.getId(), provider.getProviderScope(),
                    model, LlmUsageLogEntity.KIND_EMBED, e.getMessage());
            recordLlmTerminal(provider.getName(), model, BizMetrics.RESULT_FAIL, startNanos);
            throw e;
        }
    }

    public RerankResult rerank(RerankRequest request) {
        return rerank(request, null);
    }

    /** 专用 RERANK 出口：独立路由、Trace、计费和指标，日志只记录数量摘要。 */
    public RerankResult rerank(RerankRequest request, Long userId) {
        return rerank(request, userId, null);
    }

    /** 计划5 Step4：+projectGroupId 组池计费版本（KB ask 链路贯穿）。显式 gid 空 → 回退 BillingContext。 */
    public RerankResult rerank(RerankRequest request, Long userId, Long projectGroupId) {
        validateRerankRequest(request);
        String model = request.getModel().trim();
        request.setModel(model);
        Long uid = resolveBillingUser(userId, model);
        Long gid = resolveBillingGid(projectGroupId);
        LlmProviderInterface provider = findRerankProvider(model);
        int topN = request.getTopN() == null ? request.getDocuments().size() : request.getTopN();
        String summary = "documents=" + request.getDocuments().size() + ",topN=" + topN;
        log.info("rerank 调用 model={} provider={} userId={} {}", model, provider.getName(), uid, summary);
        requireAffordableFor(uid, gid, LlmUsageLogEntity.KIND_RERANK);
        long startNanos = System.nanoTime();
        var ragCall = ragTraceService.beginModelCall(model, provider.getName(), summary, "RERANK");
        try (ragCall) {
            RerankResult result = provider.rerank(request);
            TokenUsage usage = result.getUsage();
            Integer in;
            String status;
            if (usage != null) {
                in = usage.getPromptTokens();
                status = LlmUsageLogEntity.STATUS_SUCCESS;
            } else {
                in = TokenEstimator.estimate(request.getQuery());
                for (String document : request.getDocuments()) {
                    in += TokenEstimator.estimate(document);
                }
                status = LlmUsageLogEntity.STATUS_ESTIMATED;
            }
            billingService.onSuccess(uid, provider.getId(), provider.getProviderScope(), model,
                    LlmUsageLogEntity.KIND_RERANK, in, 0, status, null, gid);
            recordLlmSuccess(provider.getName(), model, in, 0, startNanos);
            ragCall.succeed(null, in, 0);
            return result;
        } catch (RuntimeException e) {
            ragCall.fail(e.getMessage());
            billingService.onFailure(uid, provider.getId(), provider.getProviderScope(), model,
                    LlmUsageLogEntity.KIND_RERANK, e.getMessage());
            recordLlmTerminal(provider.getName(), model, BizMetrics.RESULT_FAIL, startNanos);
            throw e;
        }
    }

    private void validateRerankRequest(RerankRequest request) {
        if (request == null || request.getModel() == null || request.getModel().isBlank()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE, "未选择重排模型");
        }
        if (request.getQuery() == null || request.getQuery().isBlank()
                || request.getDocuments() == null || request.getDocuments().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "重排请求缺少查询或候选文档");
        }
    }

    /**
     * 计划5 Step4：计费入口预检统一分派——gid 空 → 个人余额；有值 → 组池（成员身份+组池余额）。
     * 组路径的组池余额同样回喂 L7 闸门（低组池用户超在途上限在此拦，语义同个人）。
     */
    private java.math.BigDecimal requireAffordableFor(Long uid, Long projectGroupId) {
        return requireAffordableFor(uid, projectGroupId, null);
    }

    /**
     * 计划5 Step4：计费入口预检统一分派——gid 空 → 个人余额；有值 → 组池（成员身份+组池余额）。
     * 组路径的组池余额同样回喂 L7 闸门（低组池用户超在途上限在此拦，语义同个人）。
     * 17x#2（V139）：kind 非空时组路径加成员功能开关预检（真防线在 chargeGroup 同事务校验）。
     */
    private java.math.BigDecimal requireAffordableFor(Long uid, Long projectGroupId, String kind) {
        if (projectGroupId == null) {
            return walletService.requireAffordable(uid);
        }
        return groupWalletService.requireAffordableGroup(projectGroupId, uid, kind);
    }

    /**
     * 计划5 Step4：组池归属解析——显式参数 gid 优先（chat 经 LlmRequest 透传、KB ask 显式调用），
     * 空则回退 {@link BillingContext#currentGroupId()}（入口点种入 / TaskDecorator 透传 / 裸线程手工种）。
     * 与 {@link #resolveBillingUser} 同范式：KB ask 链路内部的 query embed / rerank / answer 生成
     * 调用零签名改动即归组计费。再空 = 个人计费（现状不变）。
     */
    private Long resolveBillingGid(Long projectGroupId) {
        if (projectGroupId != null) {
            return projectGroupId;
        }
        Long gid = BillingContext.currentGroupId();
        if (gid != null) {
            log.debug("组池归属：gid 取自 BillingContext gid={}", gid);
        }
        return gid;
    }

    /** 安全体系 S3 · 同步净化（null bean/内部异常均透传原文）。 */
    private String sanitizeSync(String content, Long userId) {
        if (outputSanitizer == null || content == null || content.isEmpty()) {
            return content;
        }
        return outputSanitizer.maskSync(content, userId);
    }

    /**
     * 安全体系 S3 · 流式净化：CHUNK/THINKING 内容过 masker.feed（尾 40 字符扣留下拍合并扫）；
     * 其余事件（DONE/ERROR/CITATION…）前补发 flush 尾段，保末尾内容不丢。
     * concatMap 保序；masker=null（无 bean）直通。
     */
    private Flux<StreamEvent> sanitizeStreamEvent(StreamEvent evt,
                                                  com.superprogrammer.common.security.ai.OutputSanitizer.StreamMasker masker) {
        if (masker == null || evt == null || evt.getContent() == null) {
            return Flux.just(evt);
        }
        String type = evt.getType();
        if ("CHUNK".equals(type) || "THINKING".equals(type)) {
            evt.setContent(masker.feed(evt.getContent()));
            return Flux.just(evt);
        }
        String rest = masker.flush();
        return rest.isEmpty() ? Flux.just(evt) : Flux.just(StreamEvent.chunk(rest), evt);
    }

    /**
     * 计费归户：显式 userId 优先；空则回退 {@link BillingContext#current()}（请求线程 filter 种入 /
     * 池线程 TaskDecorator 透传 / 裸线程手工 set）；再空 = 无用户上下文（系统调用），仅采不扣。
     * <p>这是「新模块免改计费」的咽喉——调用方忘传 userId 也能自动归户。无上下文时 warn 让漏扣在日志可见。
     */
    private Long resolveBillingUser(Long userId, String model) {
        if (userId != null) {
            return userId;
        }
        Long ctx = BillingContext.current();
        if (ctx != null) {
            log.debug("计费归户：userId 取自 BillingContext model={} userId={}", model, ctx);
            return ctx;
        }
        log.warn("LLM 调用无用户上下文，仅采集不扣费 model={}", model);
        return null;
    }

    /**
     * OPS-FR-03 成功终态：calls +1（success）+ tokens in/out（估算兜底也记，口径与计费一致）+ latency。
     * Micrometer 纯内存 O(1)，绝不拖垮主链路；tag 仅 provider/model（有界枚举，红线见 BizMetrics）。
     */
    private void recordLlmSuccess(String providerName, String model, Integer in, Integer out, long startNanos) {
        bizMetrics.llmCall(providerName, model, BizMetrics.RESULT_SUCCESS);
        bizMetrics.llmTokens(providerName, model, BizMetrics.DIRECTION_IN, in == null ? 0 : in);
        bizMetrics.llmTokens(providerName, model, BizMetrics.DIRECTION_OUT, out == null ? 0 : out);
        bizMetrics.llmLatency(providerName, model, elapsedSince(startNanos));
    }

    /** OPS-FR-03 终态计数 + latency（success/fail/cancel 三态互斥正好一次）。 */
    private void recordLlmTerminal(String providerName, String model, String result, long startNanos) {
        bizMetrics.llmCall(providerName, model, result);
        bizMetrics.llmLatency(providerName, model, elapsedSince(startNanos));
    }

    private static java.time.Duration elapsedSince(long startNanos) {
        return java.time.Duration.ofNanos(System.nanoTime() - startNanos);
    }

    /** 估算请求 input token（chars/4 启发式，求和各 message content）。仅 usage 缺失兜底用。 */
    private int estimateInputTokens(LlmRequest request) {
        if (request == null || request.getMessages() == null) {
            return 0;
        }
        int sum = 0;
        for (LlmMessage m : request.getMessages()) {
            if (m.getContent() != null) {
                sum += TokenEstimator.estimate(m.getContent());
            }
        }
        return sum;
    }

    private String summarizeInput(LlmRequest request) {
        if (request == null || request.getMessages() == null) return "";
        StringBuilder value = new StringBuilder();
        for (LlmMessage message : request.getMessages()) {
            value.append(message.getRole()).append(':').append(message.getContent()).append('\n');
        }
        return value.toString();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** embed 路由：只在 EMBEDDING 行注册表里按 model 找，找不到报「向量 Provider」话术（与 chat 区分）。 */
    private LlmProviderInterface findEmbedProvider(String model) {
        for (LlmProviderInterface provider : llmConfig.getEmbedProviders()) {
            if (provider.supports(model)) {
                return provider;
            }
        }
        throw new BusinessException(ErrorCode.UNPROCESSABLE,
                "所选向量模型不可用或没有启用的向量 Provider: " + model);
    }

    private LlmProviderInterface findRerankProvider(String model) {
        for (LlmProviderInterface provider : llmConfig.getRerankProviders()) {
            if (provider.supports(model)) {
                return provider;
            }
        }
        throw new BusinessException(ErrorCode.UNPROCESSABLE,
                "所选重排模型不可用或没有启用的重排 Provider: " + model);
    }

    /**
     * chat 路由（10x-1 起）：始终走全局 CHAT 注册表，不再读用户级 override。
     * <p>原因：问题单 10x-1「不再开放我的模型由个人自己配置大模型」，前端已移除入口（SettingsView），
     * 此处同步停用后端 override 路由，避免「有人配过历史 key 仍生效」的认知偏差。
     * <p>用户级相关代码（{@link #getUserProviders} / {@link #createProviderInstance} /
     * {@code UserLlmController} / {@code user_llm_providers} 表）全部保留不删，便于未来恢复：
     * 恢复时把下面的 user-override 段取消注释即可。
     * <p>EMBEDDING/VIDEO/IMAGE 行不在此处注册，故 chat 永远找不到它们（FR-003）。
     */
    private LlmProviderInterface findProvider(String model, Long userId) {
        // ===== 10x-1：用户级 override 停用（原 Step 1 已移除路由，保留注释示意恢复点） =====
        // 如需恢复个人模型配置，取消注释下面这段（并恢复 SettingsView 的 my-models Tab）：
        // if (userId != null) {
        //     List<UserLlmProviderEntity> userProviders = getUserProviders(userId);
        //     ... 原 override 逻辑 ...
        // }
        // userId 参数保留不变（向后兼容调用方签名），当前仅作日志/计费归户用途。

        // 全局 CHAT 注册表路由（原 Step 2，现为主要且唯一路径）
        for (LlmProviderInterface provider : llmConfig.getProviders()) {
            if (provider.supports(model)) {
                return provider;
            }
        }

        throw new BusinessException(ErrorCode.UNPROCESSABLE,
                "所选对话模型不可用或没有启用的对话 Provider: " + model);
    }

    private void resolveChatModel(LlmRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "LLM 请求不能为空");
        }
        String selected = request.getModel();
        if (selected != null && !selected.isBlank()) {
            request.setModel(selected.trim());
            return;
        }
        String configured = systemSettingService.getDefaultChatModel();
        if (configured == null || configured.isBlank()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "未选择模型，且管理员未配置默认对话模型");
        }
        request.setModel(configured.trim());
        log.info("请求未指定对话模型，使用管理员默认 model={}", request.getModel());
    }

    private String resolveEmbeddingModel(String selected) {
        if (selected != null && !selected.isBlank()) {
            return selected.trim();
        }
        String configured = systemSettingService.getDefaultEmbeddingModel();
        if (configured == null || configured.isBlank()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "未选择向量模型，且管理员未配置默认向量模型");
        }
        log.info("请求未指定向量模型，使用管理员默认 model={}", configured);
        return configured.trim();
    }

    private List<UserLlmProviderEntity> getUserProviders(Long userId) {
        try {
            return userLlmProviderService.listByUser(userId).stream()
                    .map(vo -> {
                        UserLlmProviderEntity e = new UserLlmProviderEntity();
                        e.setId(vo.getId());
                        e.setProviderName(vo.getProviderName());
                        e.setApiEndpoint(vo.getApiEndpoint());
                        e.setModels(vo.getModels());
                        e.setUserId(userId);
                        return e;
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("获取用户Provider失败: {}", e.getMessage());
            return List.of();
        }
    }

    private LlmProviderInterface findGlobalProvider(String name) {
        return llmConfig.getProviders().stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private LlmProviderInterface createProviderInstance(String name, String protocol, String baseUrl, String apiKey,
                                                         List<String> models, Long userProviderId) {
        if (baseUrl == null || baseUrl.isBlank()) return null;
        // 安全审计 #3：用户自填 endpoint SSRF 防护。单一咽喉点——所有用户级 provider 实例化必经此处。
        com.superprogrammer.common.security.SsrfGuard.validate(baseUrl);
        // 用户级 override：scope=USER，id=user_llm_providers.id（独立命名空间，靠 scope 区分于全局 llm_providers.id）
        return switch (resolveProtocol(name, protocol)) {
            case "ANTHROPIC" -> new ClaudeProvider(name, baseUrl, apiKey != null ? apiKey : "", models, objectMapper,
                    userProviderId, "USER");
            default -> new OpenAICompatibleProvider(name, baseUrl, apiKey != null ? apiKey : "", models, objectMapper,
                    userProviderId, "USER");
        };
    }

    private String resolveProtocol(String name, String protocol) {
        if (protocol != null && !protocol.isBlank()) {
            return protocol.trim().toUpperCase();
        }
        return "claude".equals(name) ? "ANTHROPIC" : "OPENAI_COMPATIBLE";
    }

    private List<String> parseModels(String modelsJson) {
        if (modelsJson == null || modelsJson.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(modelsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
