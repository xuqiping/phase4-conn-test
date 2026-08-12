package com.superprogrammer.chat.dto;

import lombok.Data;

/**
 * 记忆二期 P3 · Step 3 · 文件分块计数行（按记忆 GROUP BY，卡片「共N块」用，防 N+1）。
 */
@Data
public class AssetChunkCount {

    private Long assetMemoryId;
    private Long cnt;
}
