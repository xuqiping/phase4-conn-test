package com.superprogrammer.canvas.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 存版本请求（2x 五轮）。snapshot 缺省时用服务端画布当前快照（自动保存 800ms 防抖后近乎实时；
 * 显式传入则定格客户端此刻结构——与 PUT save 同信任级别，长度上限同 CanvasSaveRequest）。
 */
@Data
public class CanvasVersionCreateRequest {

    /** 版本名（可空，服务端补时间戳缺省名）。 */
    @Size(max = 64, message = "版本名最长 64 字符")
    private String label;

    /** 画布结构 JSON（最长 5MB，防撑爆；空=用服务端当前快照）。 */
    @Size(max = 5_000_000, message = "画布快照过大")
    private String snapshot;
}
