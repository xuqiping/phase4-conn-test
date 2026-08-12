package com.superprogrammer.media.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 生图同步结果（{@link com.superprogrammer.media.provider.ArkImageProvider#generate} 返回）。
 *
 * <p>图片 API 是<b>同步</b>协议（POST /v1/images/generations 直接返 data[]，无建任务/轮询），
 * 故不走 {@link MediaGenResult}（create/poll 形），用本类一次返全量结果。
 *
 * <ul>
 *   <li>{@code imageUrls} 生成图 URL 列表（response_format=url，24h 临时链接，须即时下载落地）。</li>
 *   <li>{@code generatedImages} 官方 {@code usage.generated_images}——<b>按张计费基础</b>。</li>
 *   <li>{@code outputTokens} {@code usage.output_tokens}（审计用，非计费）。</li>
 * </ul>
 */
@Data
@Builder
public class MediaImageResult {

    /** 是否成功（false 时看 errorMsg）。 */
    private boolean success;
    /** 生成图 URL 列表（成功时）。 */
    private List<String> imageUrls;
    /** 官方 generated_images（计费张数）。 */
    private Integer generatedImages;
    /** usage.output_tokens（审计）。 */
    private Long outputTokens;
    /** 失败原因（脱敏截断）。 */
    private String errorMsg;
}
