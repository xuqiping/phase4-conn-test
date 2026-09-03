package com.superprogrammer.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * C3 多轮检索旋钮（WP2，prefix {@code rag.retrieval}）。
 * 仿 {@link RagRecallProperties}：yml 可覆盖、默认值内置。
 *
 * <p>{@code maxRounds=1} 即单轮（行为=基线，零迁移零代码即时生效）；默认 2 = round0 + 至多 1 补充轮。
 * 补充轮仅在 CoverageVerifier 判「必达子意图未被 round0 证据覆盖」时触发（规则版=EXACT 带 filter 类），
 * 其余 query 一律 rounds=0 直接返回——覆盖场景零额外对象分配、零 LLM 调用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.retrieval")
public class RagRetrievalProperties {

    /** 最大检索轮数（含 round0）。1=基线单轮；运维 kill switch：调 1 即回基线，无需回滚发版。 */
    private int maxRounds = 2;

    /** 每轮补充 query 上限（规则版=未覆盖 filter 值，天然 ≤3）。 */
    private int supplementPerRound = 3;

    /** 边界邻近扩展（WP2 Step3）：截断/首尾短证据补相邻节点。 */
    private Neighbor neighbor = new Neighbor();

    @Data
    public static class Neighbor {
        /** kill switch：false → 零扩展（检索行为回到无邻近基线）。 */
        private boolean enabled = true;
        /** 「首尾短证据」判定的 content 长度阈（字符）。 */
        private int shortContentChars = 200;
        /** 单次检索最多扩入的邻近节点数（防爆证据膨胀）。 */
        private int maxNodesPerQuery = 4;
    }
}
