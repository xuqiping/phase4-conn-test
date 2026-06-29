package com.superprogrammer.chat.dto;

import lombok.Data;

import java.util.List;

/** 记忆注入预览请求：query（模拟当轮消息）+ 可选 scope（V33 项目记忆）。
 *  scope 省略 → 默认 global-only（总记忆，向后兼容）。 */
@Data
public class MemoryPreviewRequest {
    private String query;
    /** 读开关：是否含总记忆。null=true。 */
    private Boolean includeGlobal;
    /** 读开关：开启读取的项目 id 集合（经权限过滤）。 */
    private List<Long> projectIds;
}
