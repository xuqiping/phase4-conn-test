package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 跨用户波及通知（V47 计划12）。无 deleted——resolved_at 标已处理。
 * type：SUMMARY_AFFECTED_BY_recall（他人撤回 turn 波及我的 summary）/ PROJECT_DELETED_AFFECTED（项目删除影响）。
 * worker 重生完成后 UPDATE 原行置 resolved_at + message 追加「（已重生）」（不新增行，避免 badge 抖动）。
 */
@Data
@TableName("memory_notifications")
public class MemoryNotification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;             // 接收者
    private String type;             // SUMMARY_AFFECTED_BY_recall / PROJECT_DELETED_AFFECTED
    private Long refId;              // 关联 summary/project id
    private String message;
    private OffsetDateTime resolvedAt;
    private OffsetDateTime createdAt;
}
