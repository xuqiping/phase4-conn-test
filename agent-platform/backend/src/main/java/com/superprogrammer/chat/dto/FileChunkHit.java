package com.superprogrammer.chat.dto;

import lombok.Data;

/**
 * 记忆二期 P3 · Step 3 · 文件分块向量检索行（memory_asset_chunks top-k 查询投影）。
 * <p>
 * MyBatis 列名大小写不敏感映射（asset_memory_id → assetMemoryId），
 * {@code distance} 为 halfvec cosine 距离（{@code <=>}，越小越相关）。
 */
@Data
public class FileChunkHit {

    private Long id;
    private Long assetMemoryId;
    private Integer chunkNo;
    private String chunkText;
    private String pageRef;
    private Double distance;
}
