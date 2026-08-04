package com.superprogrammer.chat.dto;

import lombok.Data;

/**
 * 计划12 · F-4b 前置 · copy-to / restore 请求体（总体设计 §3.7）。
 * <p>
 * {@code projectName} 可空——空则服务端按「「原项目名」记忆拉取」命名（截 100 字符，projects.name 上限）。
 * 两个动作都是<b>自建新项目</b>再拉取：copy-to = copy 非 move（原项目零改动）；
 * restore = 移出 deleted_project_ids + 重挂新项目（仅拉 turn 不拉 summary）。
 */
@Data
public class MemoryLifecyclePullRequest {

    /** 新项目名（可空，走默认命名）。 */
    private String projectName;
}
