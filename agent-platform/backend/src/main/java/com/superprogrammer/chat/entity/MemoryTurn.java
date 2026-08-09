package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.LongArrayTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 记忆流水账（V47 计划12；V67 二期 P1 纯个人域化）。表走 BaseEntity 软删。
 * 一轮对话 = 1 INPUT + 1 OUTPUT 各一条；记忆主体表。
 * L0 = tag_ids（指向 memory_tags）；L1 = l1_summary；L2 = l2_detail；raw_content = gen 关态原文。
 * gen_done=false 的 raw turn 不参与召回（不进聚合也不兜底）。
 * <p>
 * <b>二期 P1 定案（FR-006，V67）</b>：turns <b>纯个人域</b>——个人对话全量进个人流水账，
 * 原文不出个人域；项目记忆改走 {@code memory_project_entries}（收录规则路由蒸馏）。
 * 一期四列（project_ids/born_personal/departed_project_ids/deleted_project_ids）已随 V67 DROP。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memory_turns", autoResultMap = true)
public class MemoryTurn extends BaseEntity {

    private Long userId;
    private Long sessionId;
    private String direction;        // INPUT / OUTPUT

    /** L0 标签 id 集。BIGINT[]。 */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private List<Long> tagIds;

    private String l1Summary;
    private String l2Detail;
    private String rawContent;
    private Boolean genDone;

    /** 该轮对话所用的对话 model（ChatRequest.model 落库）；后台压缩按此取，NULL 回退 memory.judge.model 默认。 */
    private String chatModel;
}
