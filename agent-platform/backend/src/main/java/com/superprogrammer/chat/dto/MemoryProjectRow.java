package com.superprogrammer.chat.dto;

import lombok.Data;

/** 记忆-项目关联行（批量查面板「所属项目」列用，避免 N+1）。 */
@Data
public class MemoryProjectRow {
    private Long memoryId;
    private Long projectId;
}
