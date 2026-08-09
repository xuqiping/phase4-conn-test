package com.superprogrammer.chat.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 计划12 · F · 流水账展示 VO（总体设计 §3.1）。
 * <p>
 * 前端「流水账」页签列表用。<b>仅本人流水账</b>（向量 7/13 ownership），含 tag label 回填。
 * {@code genDone}=false → raw 未生成（前端标 raw 徽标）。
 * 二期 P1（V67）：turns 纯个人域——projectIds/projectNames/bornPersonal 随一期项目挂载/出身标记下线。
 */
@Data
public class MemoryTurnVO {
    private Long id;
    private Long sessionId;
    /** INPUT / OUTPUT */
    private String direction;
    private List<Long> tagIds;
    /** tagIds 对应的 label 集（batch 回填防 N+1，顺序与 tagIds 对齐）。 */
    private List<String> tagLabels;
    private String l1Summary;
    private String l2Detail;
    private String rawContent;
    private Boolean genDone;
    private OffsetDateTime createdAt;
}
