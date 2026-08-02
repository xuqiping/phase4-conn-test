package com.superprogrammer.knowledge.service.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion（纯函数，无 Spring，可单测）。
 * 多路召回结果按排名融合：{@code rrf(key) = Σ_list weight * 1/(k + rank)}，rank 从 1 起。
 *
 * <p>用途：① Phase1 多 qvec 的 L0 排名融合；② Phase2/3 跨通道（L0 向量 / L1 向量 / BM25）融合
 * （各通道分数尺度不可比，RRF 按排名归一最稳）。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /** 等权融合多个有序列表（每个 list 按相关度降序，index 0 = 最佳 rank 1）。 */
    public static <T> Map<T, Double> fuse(List<List<T>> rankedLists, int k) {
        return fuseWeighted(rankedLists.stream().map(l -> new WeightedList<>(l, 1.0)).toList(), k);
    }

    /** 带通道权重的融合。 */
    public static <T> Map<T, Double> fuseWeighted(List<WeightedList<T>> lists, int k) {
        Map<T, Double> scores = new HashMap<>();
        int kk = k <= 0 ? 60 : k;
        for (WeightedList<T> wl : lists) {
            List<T> list = wl.list == null ? List.of() : wl.list;
            for (int i = 0; i < list.size(); i++) {
                int rank = i + 1;
                scores.merge(list.get(i), wl.weight / (kk + rank), Double::sum);
            }
        }
        return scores;
    }

    /** 按 RRF 分降序返回 key 列表。 */
    public static <T> List<T> sortByScoreDesc(Map<T, Double> scores) {
        List<Map.Entry<T, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return entries.stream().map(Map.Entry::getKey).toList();
    }

    public record WeightedList<T>(List<T> list, double weight) {
    }
}
