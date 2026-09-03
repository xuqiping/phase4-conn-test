package com.superprogrammer.knowledge.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 边界邻近扩展（WP2 Step3 激活）：对判为「边界」的证据节点，取邻接表中的相邻兄弟节点。
 *
 * <p>纯函数零依赖：selected=边界证据 nodeId；neighbors=nodeId→相邻节点（前/后，service 侧按
 * 同 parent 组内 id 序构建）；authorized=SQL 已按 ACTIVE/文档有效性圈定的兄弟全集
 * （同 parent 即同文档，证据过可见集 ⇒ 兄弟同过）。保序去重，selected 不重复计入。
 */
public class NeighborExpander {

    public List<Long> expand(List<Long> selected, Map<Long, List<Long>> neighbors, Set<Long> authorized) {
        LinkedHashSet<Long> out = new LinkedHashSet<>(selected);
        for (Long id : selected) {
            for (Long n : neighbors.getOrDefault(id, List.of())) {
                if (authorized.contains(n)) {
                    out.add(n);
                }
            }
        }
        return new ArrayList<>(out);
    }
}
