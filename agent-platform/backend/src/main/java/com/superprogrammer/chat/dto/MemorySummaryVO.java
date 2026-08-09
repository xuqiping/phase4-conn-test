package com.superprogrammer.chat.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 计划12 · F · 记忆总结展示 VO（总体设计 §3.4）。
 * <p>
 * 前端「总结」页签列表用。{@code status}：CLEAN 干净 / PENDING_CONFLICT 时序互斥挂起 / STALE 源 turn 被删待重生。
 * 总结恒只读自己（user_id=self，他人总结不可见防污染），故本 VO 无 ACL 字段。
 * {@code projectId}=null = 个人 scope；非空 = 项目 scope。
 */
@Data
public class MemorySummaryVO {
    private Long id;
    private Long projectId;
    private Long tagId;
    private String subject;
    private String topic;
    private String tagLabel;
    private String l1Summary;
    private String l2Detail;
    private Long sourceSummaryId;
    private List<Long> sourceTurnIds;
    /** V70 二期 P4：USER=个人总结 / PROJECT=项目共享总结（全员可读）。 */
    private String scopeOwner;
    /** CLEAN / PENDING_CONFLICT / STALE */
    private String status;
    private OffsetDateTime summarizedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
