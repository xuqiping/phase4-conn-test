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
    /** Ark 真实 token 用量；null 表示未返，须估算。 */
    private Long usageTokens;
    /** 失败原因（脱敏截断 ≤256）。 */
    private String errorMsg;
}
