package com.superprogrammer.asset.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 媒体类型受控词汇项（V60 两层设计 §C1b）。
 *
 * <p>{@link #key} = 媒体类型标签（项目内唯一，可自定义如「地图」）；
 * {@link #category} = 处理类别（系统固定 TEXT/IMAGE/VIDEO/AUDIO，决定编辑器/mime/预览/gen_meta 链路）。
 *
 * <p>同时用于 {@link ProjectVO} 返回与 {@link ProjectUpdateRequest} 提交（同构）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MediaTypeDef {
    /** 媒体类型标签 key（项目受控词汇，trim 后非空）。 */
    private String key;
    /** 处理类别：TEXT/IMAGE/VIDEO/AUDIO。 */
    private String category;
}
