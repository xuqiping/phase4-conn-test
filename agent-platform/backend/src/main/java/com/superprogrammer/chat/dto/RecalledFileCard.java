package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 记忆二期 P3 · Step 3（FR-203）· 召回命中的文件记忆卡片。
 * <p>
 * pipeline ⑥.5 产物：装配文本「文件卡片」块 + 透出给前端（Step 5 渲染卡片：图标/名称/页数/下载）。
 * 文件字节不复制，下载回链走 stored_files ACL 咽喉（{@code fileId}）。
 */
@Data
@Builder
public class RecalledFileCard {

    private Long memoryId;        // memory_asset_memories 行 id
    private String fileId;        // stored_files 自然主键（下载回链）
    private String originalName;  // 文件名
    private String fileKind;      // IMAGE/DOC/PPT/PDF/AUDIO/VIDEO/OTHER
    private int chunkCount;       // 分块数（≈页数/段数）
    private Boolean weakMemory;   // 弱记忆标（读不懂内容，仅元数据）
    private boolean fileCleaned;  // 原文件已删除（stored_files 非 ACTIVE / 行不存在）
    private boolean downloadable; // = !fileCleaned（前端据此渲染下载按钮）
    private String l1;            // 一句话总结
    private String l2;            // 结构化详述（可空）

    /** 5x 四轮 U8（C5 附件定向召回）：本卡片来自本轮消息附件 → 前端「📎 本附件」徽标 + 置顶。 */
    private Boolean attached;
    /** 附件非 READY 状态标（PROCESSING/FAILED；null=READY 正常注入）。 */
    private String attachStatus;
}
