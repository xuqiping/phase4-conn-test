package com.superprogrammer.canvas.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 故事板拼接请求（plan C13 / IC-11）。
 *
 * <p>把画布上若干视频产出物按 {@link #fileIds} 顺序首尾拼接成一个成片（基础剪辑）。
 * 每个 fileId 须为当前用户 owned 的 stored_files（SOURCE_CANVAS / MEDIA 等），后端 loadPath 逐个校验归属。
 *
 * <p>边界（plan）：深度剪辑（多轨/转场/导出剪印子草稿，需求第 9 项）是独立 plan，本接口仅做顺序拼接。
 */
@Data
public class StoryboardConcatRequest {

    /** 按序拼接的源视频 fileId 列表（≥1，≤ {@code canvas.storyboard-max-segments}，默认 20）。 */
    @NotEmpty(message = "fileIds 不能为空")
    @Size(max = 50, message = "拼接片段不能超过50个")
    private List<String> fileIds;
}
