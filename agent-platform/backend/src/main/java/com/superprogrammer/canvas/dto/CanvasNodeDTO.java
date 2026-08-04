package com.superprogrammer.canvas.dto;

import lombok.Data;

import java.util.Map;

/**
 * 画布节点 DTO（快照内节点的后端契约，C4+ 节点产出触发用）。
 *
 * <p>对应前端 {@code CanvasNode}（types/canvas.ts）。snapshot JSONB 反序列化时按此结构读节点；
 * 后端按 {@link #type} 分发生成：text/script/audio → LlmGateway，image → 生图 provider，
 * video → MediaGenTaskService。
 *
 * <p>节点类型常量与前端 5 类对齐。
 */
@Data
public class CanvasNodeDTO {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_VIDEO = "video";
    public static final String TYPE_AUDIO = "audio";
    public static final String TYPE_SCRIPT = "script";

    /** 节点 id（前端生成，画布内唯一）。 */
    private String id;
    /** 节点类型（text/image/video/audio/script）。 */
    private String type;
    /** 位置（画布坐标）。 */
    private Double positionX;
    private Double positionY;
    /** 节点数据（prompt/ratio/duration/fileId 等按类型不同；整存整取，不强 schema）。 */
    private Map<String, Object> data;
}
