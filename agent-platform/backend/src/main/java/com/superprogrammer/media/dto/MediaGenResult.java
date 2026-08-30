package com.superprogrammer.media.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 媒体生成任务查询结果（provider 层统一态）。
 *
 * <p>{@code status} 统一成内部状态机枚举字符串，屏蔽各 provider（Ark/未来 Seedream）原生态差异：
 * <ul>
 *   <li>{@code PENDING/RUNNING} — 未到终态，worker 继续轮询。</li>
 *   <li>{@code SUCCEEDED} — 终态成功，{@code resultUrl} 非空（Ark OSS 临时链接，须即时下载落地）。</li>
 *   <li>{@code FAILED} — 终态失败，{@code errorMsg} 带原因（已脱敏截断）。</li>
 * </ul>
 * {@code usageTokens}：Ark 返 {@code usage.total_tokens} 则非 null（status_flag=SUCCESS）；
 * null 表示 Ark 未返，worker 按像素公式估算（status_flag=ESTIMATED）。
 */
@Data
@Builder
public class MediaGenResult {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    private String status;
    /** 成功时的结果资源 URL（视频临时链接）。 */
    private String resultUrl;
    /**
     * HHX-5：成功时的结果文本（仅 Context-IR——增强后的结构化提示词，无视频 URL）。
     * worker 将其落 .md 文件走 result_file_id 链路；视频任务恒 null。
     */
    private String resultText;
    /** Ark 真实 token 用量；null 表示未返，须估算。 */
    private Long usageTokens;
    /**
     * HHX-9：Context-IR 输入/输出 token 分计（usage.prompt_tokens/completion_tokens），
     * CHAT 价（输入/输出分价）实扣用；视频任务（按秒计费）恒 null。
     */
    private Long usageInputTokens;
    private Long usageOutputTokens;
    /** 失败原因（脱敏截断 ≤256）。 */
    private String errorMsg;
}
