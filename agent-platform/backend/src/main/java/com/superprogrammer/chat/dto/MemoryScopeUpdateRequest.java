package com.superprogrammer.chat.dto;

import lombok.Data;

import java.util.List;

/** 记忆 scope 归属更新请求（V33）：替换该记忆全部 scope（is_global + projectIds）。
 *  统一覆盖「升级为 global」/「加入项目」/「关闭 global」。 */
@Data
public class MemoryScopeUpdateRequest {
    /** 是否总记忆可见。 */
    private Boolean isGlobal;
    /** 挂载的项目 id 集合。 */
    private List<Long> projectIds;
}
