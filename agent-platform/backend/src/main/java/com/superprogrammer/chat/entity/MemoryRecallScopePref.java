package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import com.superprogrammer.common.typehandler.LongArrayTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 记忆召回 scope 用户偏好（V50 计划12 · D-7）。表走 BaseEntity 软删 + version。
 * <p>
 * 1:1 用户偏好（{@code user_id} UNIQUE WHERE deleted=0），跨会话沿用——设计 §3.3 line 113
 * 「保留上次选择，新会话沿用；首次无历史默认 {个人}」。upsert 语义（无则插/有则改，业务不主动删）。
 * <p>
 * <b>方案 A</b>（per-user 独立表）：scope 多选是用户级强一致偏好，不宜 Redis 缓存语义；
 * SystemSetting 全局表无 user_id 列，混用污染全局配置语义。
 * <p>
 * 字段对齐 {@link com.superprogrammer.chat.dto.MemoryRecallScopeRequest}（resolver 读时 null 兜底默认）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memory_recall_scope_prefs", autoResultMap = true)
public class MemoryRecallScopePref extends BaseEntity {

    private Long userId;
    private Boolean personalOn;
    private String direction;
    private Integer relativeDays;
    private OffsetDateTime twStart;
    private OffsetDateTime twEnd;
    private Boolean includeDeparted;

    /** 项目多选集。BIGINT[]。⚠️ 写入须显式 typeHandler（V33 教训）。 */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private List<Long> projectIds;
}
