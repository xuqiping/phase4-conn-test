package com.superprogrammer.chat.dto;

import lombok.Data;

/**
 * 二期 P2：流水账「被哪个项目收录」批量查询行。
 * <p>
 * 一条流水账（memory_turns）可能被收录进多个项目（同项目多 tag 也会产生多条条目），
 * service 层按 {@code turnId} 分组并对 {@code projectId} 去重。
 */
@Data
public class TurnProjectIndexRow {
    private Long turnId;
    private Long projectId;
    private String projectName;
}
