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
 * 项目记忆条目（V65 记忆二期 P1）。表走 BaseEntity 软删。
 * <p>
 * 路由 LLM 蒸馏产出的项目记忆，<b>项目资产</b>非用户资产——成员离职条目留在项目；
 * 原文不出个人域：本表永不含 raw_content，成员即可读（ACTIVE 成员），DEPARTED 失读权。
 * <p>
 * 状态机：ACTIVE（置信度≥0.8 直接收录/审核通过）/ PENDING_REVIEW（灰区待裁决）；
 * 审核「弃」= 软删（deleted=1）。tag 归一在作者个人标签库（D2 案 A），条目只存 tag_ids。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "memory_project_entries", autoResultMap = true)
public class MemoryProjectEntry extends BaseEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String CONTENT_TYPE_TEXT = "TEXT";
    public static final String CONTENT_TYPE_FILE = "FILE";

    private Long projectId;          // 条目归属项目（项目资产，随项目 CASCADE）
    private Long authorUserId;       // 溯源谁聊出来的（仅元数据展示）
    private Long sourceTurnId;       // 回指个人流水账 OUTPUT turn（软链，仅作者可顺藤）
    private Long sourceTurnInputId;  // 5x #2：配对 INPUT turn 软链（entry 覆盖整轮，两 turn 均可反查收录）

    /** 标签 id 集（作者个人标签库）。BIGINT[] 走 LongArrayTypeHandler。 */
    @TableField(typeHandler = LongArrayTypeHandler.class)
    private List<Long> tagIds;

    private String l1Summary;        // 蒸馏 L1（生成时即脱敏）
    private String l2Detail;         // 蒸馏 L2（同上）
    private Double confidence;       // 路由置信度 0~1
    private String status;           // ACTIVE / PENDING_REVIEW
    private String contentType;      // TEXT / FILE（P3 文件记忆收录）
    private String fileId;           // contentType=FILE 时指向 stored_files.file_id（VARCHAR，V69 修正类型）
    private Long reviewedBy;         // 审核人（owner/admin 收/弃留痕）
    private OffsetDateTime reviewedAt;

    /** 条目沿用源 turn 的对话 model；后台条目级压缩按此取，NULL 回退 memory.judge.model 默认。 */
    private String chatModel;
}
