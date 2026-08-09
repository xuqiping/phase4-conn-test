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

    /** tag 兜底：null/空白归一为 unknown，防 Micrometer 拒 null tag 抛异常拖垮主链路。 */
    private static String safe(String tag) {
        return tag == null || tag.isBlank() ? "unknown" : tag;
    }
}
