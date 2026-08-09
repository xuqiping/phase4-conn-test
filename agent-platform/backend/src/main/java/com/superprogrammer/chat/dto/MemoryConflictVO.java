package com.superprogrammer.chat.dto;

import lombok.Data;

import java.util.List;

/** 冲突分组视图（GET /memories/conflicts 返回）。 */
@Data
public class MemoryConflictVO {
    private Long conflictId;
    private String block;
    private List<MemoryCandidateVO> candidates;
    private String status;       // FLAGGED
    private String askText;
    private String createdAt;
    /** 二期 P4（FR-303）：true=项目共享总结冲突（裁决权=项目 owner/admin），前端 badge 用。 */
    private Boolean projectShared;
}
