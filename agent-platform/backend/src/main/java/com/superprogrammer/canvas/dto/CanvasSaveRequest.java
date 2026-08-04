package com.superprogrammer.canvas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存画布请求（全量覆盖 name + snapshot）。
 *
 * <p>snapshot 为画布结构 JSON 字符串（{nodes,edges,viewport}），只存 fileId 引用不嵌 base64。
 * 长度上限防撑爆 JSONB（plan 安全清单「快照大小上限」）。
 */
@Data
public class CanvasSaveRequest {

    @NotBlank(message = "画布名不能为空")
    @Size(max = 128, message = "画布名最长 128 字符")
    private String name;

    /** 画布结构 JSON（最长 5MB，防撑爆）。 */
    @Size(max = 5_000_000, message = "画布快照过大")
    private String snapshot;
}
