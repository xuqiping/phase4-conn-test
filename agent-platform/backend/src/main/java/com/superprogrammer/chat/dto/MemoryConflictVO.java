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
}
