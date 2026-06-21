package com.superprogrammer.chat.dto;

/**
 * 最近邻块匹配结果行（UserMemoryMapper.findNearestBlock）。
 * distance = pgvector 余弦距离（embedding <=>），similarity = 1 - distance。
 */
public record MemoryBlockHit(String blockLabel, double distance) {
    public double similarity() {
        return 1.0 - distance;
    }
}
