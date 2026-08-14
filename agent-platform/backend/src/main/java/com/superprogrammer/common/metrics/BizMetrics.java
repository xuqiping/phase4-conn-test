package com.superprogrammer.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 业务指标统一注册类（运维系统 OPS-FR-03~07 骨架）。
 *
 * <p><b>高基数红线</b>：userId / traceId / agentId / sessionId / 文档ID 等<b>永远不进 metric tag</b>——
 * 每个新 tag 值就是一条新时序，高基数值会撑爆 Prometheus。高基数值只进日志/span。
 * 本类所有 tag 只允许有界枚举集：provider 名、model 名、result、status、direction。</p>
 *
 * <p>命名约定：Micrometer 点分名 → Prometheus 下划线名（{@code llm.calls} → {@code llm_calls_total}）。
 * Counter 以 {@code .total} 结尾语义化（Prometheus 会自动再加 _total 后缀）。</p>
 *
 * <p>埋点纪律：Counter/Timer 均为 O(1) 内存操作，主链路零 IO；Gauge 回调只允许轻查询。
 * 新增指标必须在本类集中声明，禁止各处散写 {@code registry.counter(...)}。</p>
 */
@Component
public class BizMetrics {

    /** LLM 调用终态。正好一次：一次调用在终态分支计一次，不重不漏。 */
    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAIL = "fail";
    /** 流式调用被中断（客户端断开/取消）。 */
    public static final String RESULT_CANCEL = "cancel";

    /** token 方向。 */
    public static final String DIRECTION_IN = "in";
    public static final String DIRECTION_OUT = "out";

    /** 媒体任务类型（有界枚举，tag 安全）。 */
    public static final String MEDIA_VIDEO = "video";
    public static final String MEDIA_IMAGE = "image";
    public static final String MEDIA_EDIT = "edit";

    /** 索引结果。void=job 作废（节点变更/失活接管），非失败非成功。 */
    public static final String INDEX_SUCCESS = "success";
    public static final String INDEX_FAIL = "fail";
    public static final String INDEX_VOID = "void";

    private final MeterRegistry registry;
    /** queue.depth Gauge 全局只注册一次（重复 register 会覆盖回调）。 */
    private final AtomicBoolean queueDepthRegistered = new AtomicBoolean(false);

    public BizMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ---------- OPS-FR-03：LLM ----------

    /** LLM 调用计数：llm_calls_total{provider,model,result}。在终态分支调用，正好一次。 */
    public void llmCall(String provider, String model, String result) {
        Counter.builder("llm.calls")
                .description("LLM 调用次数（终态正好一次）")
                .tags("provider", safe(provider), "model", safe(model), "result", safe(result))
                .register(registry)
                .increment();
    }

    /** LLM token 消耗：llm_tokens_total{provider,model,direction=in|out}。仅成功且有 usage 时记。 */
    public void llmTokens(String provider, String model, String direction, long count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("llm.tokens")
                .description("LLM token 消耗（按方向）")
                .tags("provider", safe(provider), "model", safe(model), "direction", safe(direction))
                .register(registry)
                .increment(count);
    }

    /** LLM 延迟直方图：llm_latency（bucket 100ms~60s，见 MetricsBucketConfig）。 */
    public void llmLatency(String provider, String model, Duration duration) {
        Timer.builder("llm.latency")
                .description("LLM 调用延迟")
                .tags("provider", safe(provider), "model", safe(model))
                .register(registry)
                .record(duration);
    }

    // ---------- OPS-FR-04：工作流 ----------

    /** 工作流终态计数：workflow_executions_total{status}（status=SUCCESS/FAILED 等终态枚举）。 */
    public void workflowExecution(String status) {
        Counter.builder("workflow.executions")
                .description("工作流执行终态次数")
                .tags("status", safe(status))
                .register(registry)
                .increment();
    }

    /** 工作流执行耗时：workflow_duration。 */
    public void workflowDuration(Duration duration) {
        Timer.builder("workflow.duration")
                .description("工作流执行耗时")
                .register(registry)
                .record(duration);
    }

    // ---------- OPS-FR-05：索引队列 ----------

    /**
     * 注册索引队列深度 Gauge：knowledge_index_queue_depth。启动即注册（否则首次采集前无值/NaN）。
     * 回调由调用方提供（轻查询 count），全局只注册一次，重复调用静默忽略。
     */
    public void registerIndexQueueDepth(Supplier<Number> countSupplier) {
        if (!queueDepthRegistered.compareAndSet(false, true)) {
            return;
        }
        // Gauge.builder 持强引用 supplier，防 GC 后读数消失
        Gauge.builder("knowledge.index.queue.depth", countSupplier, s -> {
                    Number n = s.get();
                    return n == null ? 0.0 : n.doubleValue();
                })
                .description("索引队列当前积压深度（PENDING+可认领）")
                .register(registry);
    }

    /** 索引完成计数：knowledge_indexed_total{result=success|fail|void}。每轮处理结束记一次。 */
    public void indexed(String result) {
        Counter.builder("knowledge.indexed")
                .description("索引 job 处理结果次数")
                .tags("result", safe(result))
                .register(registry)
                .increment();
    }

    /** 知识分块产出数：granularity 仅允许 S1/C2/E3 等固定枚举，禁止文档 ID 等高基数值。 */
    public void knowledgeChunked(String granularity, long count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("knowledge.chunks")
                .description("知识文档分块产出数（按固定粒度）")
                .tags("granularity", safe(granularity))
                .register(registry)
                .increment(count);
    }

    /** 单文档节点写入与分块耗时；无 tag，避免高基数。 */
    public void knowledgeChunkDuration(Duration duration) {
        Timer.builder("knowledge.chunk.duration")
                .description("知识文档分块与节点写入耗时")
                .register(registry)
                .record(duration);
    }

    /** RAG 各阶段结果计数；stage/result 仅允许代码枚举，禁止 userId/traceId/KB 名进入 tag。 */
    public void ragPipeline(String stage, String result) {
        Counter.builder("rag.pipeline")
                .description("RAG 召回、重排、覆盖、降级和删除 SLA 结果")
                .tags("stage", safe(stage), "result", safe(result))
                .register(registry)
                .increment();
    }

    /** RAG 阶段耗时；stage 为有限枚举，例如 retrieval/rerank/coverage。 */
    public void ragStageLatency(String stage, Duration duration) {
        Timer.builder("rag.stage.latency")
                .description("RAG 各阶段耗时")
                .tags("stage", safe(stage))
                .register(registry)
                .record(duration);
    }

    /** 在线反馈计数；分类和状态都是固定枚举，反馈正文绝不进入指标。 */
    public void ragFeedback(String category, String status) {
        Counter.builder("rag.feedback")
                .description("RAG 在线反馈待审核队列计数")
                .tags("category", safe(category), "status", safe(status))
                .register(registry)
                .increment();
    }

    // ---------- OPS-FR-06：记忆管线 ----------

    /** 记忆管线耗时：memory_pipeline_duration。 */
    public void memoryPipelineDuration(Duration duration) {
        Timer.builder("memory.pipeline.duration")
                .description("记忆管线（整合/摄入）耗时")
                .register(registry)
                .record(duration);
    }

    /** 记忆管线异常事件：memory_incidents_total。 */
    public void memoryIncident() {
        Counter.builder("memory.incidents")
                .description("记忆管线异常事件次数")
                .register(registry)
                .increment();
    }

    // ---------- OPS-FR-07：认证 ----------

    /** 登录结果：auth_login_total{result=success|fail}。 */
    public void authLogin(String result) {
        Counter.builder("auth.login")
                .description("登录次数（按结果）")
                .tags("result", safe(result))
                .register(registry)
                .increment();
    }

    /** 注册限流触发：auth_register_rate_limited_total（安全审计 #9 联动）。 */
    public void registerRateLimited() {
        Counter.builder("auth.register.rate_limited")
                .description("注册限流触发次数")
                .register(registry)
                .increment();
    }

    /** 登录锁定/封禁：auth_login_locked_total{scope=account|ip}（安全体系 S1 · SEC-FR-001）。 */
    public void authLoginLocked(String scope) {
        Counter.builder("auth.login.locked")
                .description("登录防爆破触发次数（按维度）")
                .tags("scope", safe(scope))
                .register(registry)
                .increment();
    }

    // ---------- 安全加固（11x · security domain） ----------

    /** API 限流触发：api_rate_limited_total{action}。action=有界注解枚举（非 URL 路径，防高基数）。 */
    public void apiRateLimited(String action) {
        Counter.builder("api.rate.limited")
                .description("API 限流触发次数（按动作）")
                .tags("action", safe(action))
                .register(registry)
                .increment();
    }

    /** 安全事件入库：security_events_raised_total{type,severity}。type=规则码（有界），severity=LOW/MEDIUM/HIGH/CRITICAL。 */
    public void securityEventRaised(String eventType, String severity) {
        Counter.builder("security.events.raised")
                .description("安全事件入库次数（按类型与严重度）")
                .tags("type", safe(eventType), "severity", safe(severity))
                .register(registry)
                .increment();
    }

    /** IP 封禁：security_ip_blocked_total{source=AUTO|MANUAL}。 */
    public void ipBlocked(String source) {
        Counter.builder("security.ip.blocked")
                .description("IP 封禁次数（按来源）")
                .tags("source", safe(source))
                .register(registry)
                .increment();
    }

    /** 账号锁定/封号：security_account_locked_total{action=lock|ban}。 */
    public void accountLocked(String action) {
        Counter.builder("security.account.locked")
                .description("账号锁定/封号次数（按动作）")
                .tags("action", safe(action))
                .register(registry)
                .increment();
    }

    /** 安全体系 S3 · SEC-FR-051：KB 文档因注入特征被隔离次数。 */
    public void kbQuarantined() {
        Counter.builder("security.ai.kb.quarantined")
                .description("KB 文档因提示注入特征被隔离次数")
                .register(registry)
                .increment();
    }

    /** 安全体系 S3 · SEC-FR-052：LLM 输出敏感模式命中打码次数。 */
    public void outputMasked() {
        Counter.builder("security.ai.output.masked")
                .description("LLM 输出敏感模式命中打码次数")
                .register(registry)
                .increment();
    }

    /** 安全体系 S3 · SEC-FR-053：LLM 输出命中静态 prompt 指纹遮蔽次数。 */
    public void promptLeak() {
        Counter.builder("security.ai.prompt.leak")
                .description("LLM 输出命中 system prompt 指纹遮蔽次数")
                .register(registry)
                .increment();
    }

    /** 安全体系 S4 · SEC-FR-031：上传 magic number 嗅探拒收（tag: reason=mismatch）。 */
    public void uploadMagicDenied(String reason) {
        Counter.builder("security.upload.magic.denied")
                .description("上传内容与声明类型不符拒收次数")
                .tags("reason", safe(reason))
                .register(registry)
                .increment();
    }

    /** 安全体系 S4 · SEC-FR-031：嗅探无法判定（octet-stream）放行观察计数。 */
    public void uploadMagicUnknown() {
        Counter.builder("security.upload.magic.unknown")
                .description("上传 magic number 嗅探无法判定的放行次数（观察后决定是否收紧）")
                .register(registry)
                .increment();
    }

    /** 安全体系 S4 · SEC-FR-033：per-user 存储配额超限拒收次数。 */
    public void uploadQuotaDenied() {
        Counter.builder("security.upload.quota.denied")
                .description("用户存储配额超限拒收次数")
                .register(registry)
                .increment();
    }

    /** 安全体系 S5 · SEC-FR-004+（A4 旋转）：refresh 旋转成功次数。 */
    public void authRefreshRotated() {
        Counter.builder("security.auth.refresh.rotated")
                .description("refresh token 旋转签发次数")
                .register(registry)
                .increment();
    }

    /** 安全体系 S5 · SEC-FR-004+（A4 旋转）：已被旋转的旧 refresh 再次使用（重放检出，token 可能被偷）。 */
    public void authRefreshReplayed() {
        Counter.builder("security.auth.refresh.replayed")
                .description("refresh token 重放检出次数")
                .register(registry)
                .increment();
    }

    /** 安全体系 S5 · SEC-FR-006（A6 TOTP）：两步登录第二屏校验结果（result=success/fail）。 */
    public void authMfaVerify(String result) {
        Counter.builder("security.auth.mfa.verify")
                .tag("result", result)
                .description("TOTP 两步登录验证码校验结果")
                .register(registry)
                .increment();
    }

    /** 11x 加固 P3-C8：安全监控队列满丢弃计数（无 tag，零基数风险）。 */
    public void securityEventDropped() {
        Counter.builder("security.event.dropped")
                .description("安全事件队列满被丢弃次数")
                .register(registry)
                .increment();
    }

    /** 11x 加固 P4-C11：钉钉告警发送成功（无 tag）。 */
    public void alertSent() {
        Counter.builder("security.alert.sent")
                .description("钉钉告警发送成功次数")
                .register(registry)
                .increment();
    }

    /** 11x 加固 P4-C11：钉钉告警发送失败（持续上涨 → meta 告警盯此指标）。 */
    public void alertSendFailed() {
        Counter.builder("security.alert.send_failed")
                .description("钉钉告警发送失败次数")
                .register(registry)
                .increment();
    }

    // ---------- 媒体生成/剪辑（合并收尾项 6：beifen 媒体域接入统一指标） ----------

    /** 媒体任务提交：media_task_submitted_total{kind=video|image|edit}。落库成功后记。 */
    public void mediaSubmit(String kind) {
        Counter.builder("media.task.submitted")
                .description("媒体任务提交次数（按类型）")
                .tags("kind", safe(kind))
                .register(registry)
                .increment();
    }

    /** 媒体任务终态：media_task_terminal_total{kind,result}。worker 每次处理终态正好一次（重试按次计）。 */
    public void mediaTaskTerminal(String kind, String result) {
        Counter.builder("media.task.terminal")
                .description("媒体任务终态次数（按类型与结果）")
                .tags("kind", safe(kind), "result", safe(result))
                .register(registry)
                .increment();
    }

    /** 媒体任务端到端耗时（创建→终态，含排队）：media_task_duration{kind}。 */
    public void mediaTaskDuration(String kind, Duration duration) {
        Timer.builder("media.task.duration")
                .description("媒体任务端到端耗时（创建→终态）")
                .tags("kind", safe(kind))
                .register(registry)
                .record(duration);
    }

    /** tag 兜底：null/空白归一为 unknown，防 Micrometer 拒 null tag 抛异常拖垮主链路。 */
    private static String safe(String tag) {
        return tag == null || tag.isBlank() ? "unknown" : tag;
    }
}
