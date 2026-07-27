package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 项目记忆读取授权（V49 计划12·迭代 I1）。reader→target 的显式授权行。
 * <p>
 * 无 deleted——撤销 = DELETE 行（append-only + 删，同 members/coverage 风格）。
 * <p>
 * 语义见总体设计 §3.6 + §6 向量 14：
 * <ul>
 *   <li>owner 兜底全读，无需本表行。</li>
 *   <li>admin/member 按本表 reader→target 集 ∪ {自己}。</li>
 *   <li>{@code recall_admin=true} 仅多「配 ACL」权（I2 端点判），读不扩。</li>
 *   <li>DEPARTED 曾赋权的 target 保行（保交接），L10 开关在 I3 接入时过滤。</li>
 * </ul>
 *
 * @see com.superprogrammer.chat.service.internal.MemoryRecallAclResolver
 */
@Data
@TableName("memory_recall_acl")
public class MemoryRecallAcl {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long readerUserId;   // 被授权读者
    private Long targetUserId;   // 被读作者
    private Long createdBy;      // 授权操作人（审计）
    private OffsetDateTime createdAt;
}
