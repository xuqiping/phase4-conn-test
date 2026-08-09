package com.superprogrammer.chat.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.superprogrammer.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文件分块·深读层（V69 记忆二期 P3）。表走 BaseEntity 软删，随记忆 CASCADE。
 * <p>
 * 每页/每段一条：页要点 + halfvec(2048) 向量 + page_ref 语义锚点（「第12页」/「00:03:25」）。
 * 文件记忆召回命中且 reflect 判需深读时 → 向量 top-k 进 prompt，回答引用须带 page_ref
 * （D-19.12 幻觉对冲：锚点必须能反向定位原文位置）。
 * <p>
 * chunk_embedding 为 halfvec 类型，实体不映射该列（向量检索走自定义 XML SQL，
 * 写入同理——沿用 memory_turns 的 halfvec 处理模式）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("memory_asset_chunks")
public class MemoryAssetChunk extends BaseEntity {

    private Long assetMemoryId;    // 所属文件记忆
    private Integer chunkNo;       // 顺序号（页序/段序）
    private String chunkText;      // 页要点/段落文本
    private String pageRef;        // 语义锚点（页码/时间戳），回答引用必带
}
