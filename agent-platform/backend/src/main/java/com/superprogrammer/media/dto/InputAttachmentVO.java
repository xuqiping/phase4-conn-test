package com.superprogrammer.media.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 任务输入附件摘要。只回显引用关系，不包含 data URI 或文件正文；previewUrl 仍会经过文件接口二次鉴权。
 */
@Data
@Builder
public class InputAttachmentVO {
    private String fileId;
    private String kind;
    private String frameRole;
    private String name;
    private String previewUrl;
}
