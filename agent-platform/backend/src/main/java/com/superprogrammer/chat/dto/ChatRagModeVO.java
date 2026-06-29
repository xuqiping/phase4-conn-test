package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 用户侧 RAG/记忆模式只读视图（非 admin）。
 * 仅暴露全局总开关，供前端会话级开关做「null=继承全局」的联动显示。
 * 写入仍走 admin 端点 {@code /system/settings/rag-memory}。
 */
@Data
@Builder
public class ChatRagModeVO {
    /** 全局总开关（会话级 ragEnabled=null 时继承此值）。 */
    private Boolean globalEnabled;
}
