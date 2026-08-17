package com.superprogrammer.billing.context;

/**
 * 计费归户上下文：跨层自动传播当前调用用户 userId。
 *
 * <p>解决「调用方忘传 userId → 计费盲区」的结构性漏洞。三层填充：
 * <ol>
 *   <li>{@link BillingContextFilter} 在请求入口从 SecurityContext principal（= Long userId）种入；</li>
 *   <li>{@link BillingContextTaskDecorator} 把提交线程的 userId 透传给线程池子线程；</li>
 *   <li>裸 {@code new Thread()}（SSE 流式）手工 set/clear（参照 SecurityContextHolder 范式）。</li>
 * </ol>
 *
 * <p>{@link com.superprogrammer.llm.LlmGateway} 6 出口：当 userId 形参为 null 时回退读 {@link #current()}，
 * 故<b>新模块只要调 gateway 即自动归户计费，无需手传 userId、无需写计费代码</b>。
 * current() 返 null 表示无用户上下文（系统调用/异步无主）→ 仅采不扣。
 *
 * <p>线程局部（ThreadLocal），与 Spring Security 默认 MODE_THREADLOCAL 一致——故异步线程必须显式传播，
 * 不会自动继承（这是有意为之：防止用户身份跨任务串号）。
 *
 * <p>计划5 Step4：新增<b>组池归属 gid</b>（projectGroupId）第二槽位。与 userId 同生命周期同传播路径
 * （Filter 不种 gid——它来自请求体非 principal；入口点手工种，TaskDecorator/裸线程随 userId 一起透传）。
 * LlmGateway 出口「显式参数 gid 优先，空回退 {@link #currentGroupId()}」——与 userId 的
 * {@code resolveBillingUser} 同范式，KB ask 链路内部 embed/rerank/answer 调用零签名改动即归组计费。
 */
public final class BillingContext {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();
    /** 计划5 Step4：组池归属（null=个人计费）。 */
    private static final ThreadLocal<Long> GROUP_HOLDER = new ThreadLocal<>();

    private BillingContext() {}

    /** 种入当前线程的计费 userId（通常 = 请求用户）。 */
    public static void set(Long userId) {
        HOLDER.set(userId);
    }

    /** 计划5 Step4：同时种入 userId + 组池 gid（入口点便捷重载，gid null=个人）。 */
    public static void set(Long userId, Long projectGroupId) {
        HOLDER.set(userId);
        GROUP_HOLDER.set(projectGroupId);
    }

    /** 计划5 Step4：单独种/换组池 gid（局部段落切组计费用；null=回到个人）。 */
    public static void setGroup(Long projectGroupId) {
        GROUP_HOLDER.set(projectGroupId);
    }

    /** 取当前线程的计费 userId；未种入返 null（系统调用/异步无主）。 */
    public static Long current() {
        return HOLDER.get();
    }

    /** 计划5 Step4：取当前线程的组池 gid；未种入返 null（个人计费）。 */
    public static Long currentGroupId() {
        return GROUP_HOLDER.get();
    }

    /** 清除当前线程的计费 userId。线程池/请求结束时必调，防串号（线程复用）。 */
    public static void clear() {
        HOLDER.remove();
        GROUP_HOLDER.remove();
    }
}
