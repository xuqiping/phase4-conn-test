package com.superprogrammer.canvas.dto;

import lombok.Data;

/**
 * 单个标注框（2x 四轮 S7 / spec §6.2，ANNOTATE 合成请求的元素）。
 *
 * <p>x/y/w/h 为归一化 0-1（相对源图，与 crop 同口径——前端按 stage 尺寸归一化，
 * 服务端按源图自然像素换算）；color 为 8 色板白名单键（red/orange/yellow/green/cyan/blue/purple/magenta，
 * service 层 {@code VideoFrameService.ANNOTATE_COLORS} 校验）。
 * 逐框中文指令不上传——留在前端拼 AI prompt（spec §6.2 出口②），服务端只画框+序号。
 */
@Data
public class AnnotateBoxDTO {

    /** 左上角 x（归一化 0-1）。 */
    private Double x;

    /** 左上角 y（归一化 0-1）。 */
    private Double y;

    /** 宽（归一化 0-1，>0）。 */
    private Double w;

    /** 高（归一化 0-1，>0）。 */
    private Double h;

    /** 颜色键（8 色板白名单）。 */
    private String color;
}
