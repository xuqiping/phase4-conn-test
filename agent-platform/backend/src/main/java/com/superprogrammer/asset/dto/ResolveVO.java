package com.superprogrammer.asset.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 资产引用解析 VO（plan §S7 / FR-009，设计方案 §八「库→画布」+ §六「版本快照」）。
 *
 * <p>库→画布引用时前端调 resolve 拿「该资产当前/指定版本」的内容与文件指针，
 * 注入画布节点（版本快照语义——资产迭代到新版不影响已引用方）。
 *
 * <p>viewer 也可读（loadAccessible，只读引用，设计方案 §7.2）。
 * 文件类返 {@link #fileId}+{@link #url}；文本类（PROMPT/SCRIPT）返 {@link #content}。
 */
@Data
@Builder
public class ResolveVO {

    /** 资产 id。 */
    private Long assetId;

    /** 内容类型：PROMPT/SCRIPT/IMAGE/VIDEO/AUDIO。 */
    private String mediaType;

    /** 解析到的版本号（快照锁定）。 */
    private Integer version;

    /** 文件类资产的文件 fileId（stored_files）。 */
    private String fileId;

    /** 文件访问 URL（/api/files/{fileId}）。 */
    private String url;

    /** 文本类资产的正文 JSON（提示词正文/剧本分场）。 */
    private String content;

    /** 资产名（节点徽标「来自资产·xx v2」用）。 */
    private String name;
}
