package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.LongArrayTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 记忆流水账（V47 计划12）。表走 BaseEntity 软删。
 * 一轮对话 = 1 INPUT + 1 OUTPUT 各一条；记忆主体表。
 * L0 = tag_ids（指向 memory_tags）；L1 = l1_summary；L2 = l2_detail；raw_content = gen 关态原文。
 * gen_done=false 的 raw turn 不参与召回（不进聚合也不兜底）。
 * project_ids 多挂共享（经 ACL 只读）；born_personal 出身标记（写入定死，卸空转 true）。
 * ⚠️ BIGINT[] 写入：LambdaUpdateWrapper 不读 @TableField typeHandler（V33 教训）→ 更新走 Mapper 显式 set 带 typeHandler。
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

    /** 项目挂载槽（空=个人私有）。BIGINT[]。 */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private List<Long> projectIds;

    /** 出身标记，写入定死。 */
    private Boolean bornPersonal;

    /** 作者已离职项目 id（追加不改 project_ids）。BIGINT[]。 */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private List<Long> departedProjectIds;

    /** 被删项目 id（保留挂载，scope 召回自然排除）。BIGINT[]。 */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private List<Long> deletedProjectIds;
}
