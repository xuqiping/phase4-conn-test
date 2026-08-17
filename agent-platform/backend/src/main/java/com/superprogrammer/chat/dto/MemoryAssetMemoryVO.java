package com.superprogrammer.chat.dto;

import com.superprogrammer.chat.entity.MemoryAssetMemory;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 5x 四轮 C6：我的文件记忆列表行（记忆面板「文件记忆」页签）。
 * <p>
 * 在 {@link MemoryAssetMemory} 实体字段之上增补 {@code projectNames}——该文件被哪些
 * <b>本人可访问</b>项目收录（ACTIVE FILE 条目反查，非成员项目不透名）；
 * 空列表=未收录（前端不渲染徽标，不显示「未收录」负信息）。
 */
@Data
@Builder
public class MemoryAssetMemoryVO {

    private Long id;
    private String fileId;
    private String fileKind;
    private String originalName;
    private String l1Summary;
    private String l2Detail;
    private List<Long> tagIds;
    private String ingestStatus;     // PROCESSING / READY / FAILED
    private String ingestError;
    private Integer retryCount;
    private Boolean weakMemory;
    private String createdAt;        // ISO-8601（前端 toLocaleString 展示）

    /** 收录于哪些项目（仅 ACTIVE 成员域内；空=未收录，前端不渲染徽标）。 */
    private List<String> projectNames;
}
