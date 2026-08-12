package com.superprogrammer.knowledge.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagConfig §7 预算/召回常量 + B1 effectiveContextCap 机械校验。
 * 这些常量是检索管线多步不变式的数值锚（B3=D×cap、B1=cap、A2=abstain），改一个会连锁断言。
 */
class RagConfigTest {

    private final RagConfig config = new RagConfig();

    @Test
    void b1_effectiveContextCap_isMinOfContextAndModelReserve() {
        // min(6000, 32000-1200) = min(6000, 30800) = 6000
        assertEquals(6000, config.computeEffectiveContextCap());
    }

    @Test
    void recallBounds_matchB3Invariant() {
        // B3: maxRerankPairs 必须为 denseTopD × perDocL2Cap（5×20=100），否则 step6 断言错位
        assertEquals(100, config.getMaxRerankPairs());
        assertEquals(config.getDenseTopD() * config.getPerDocL2Cap(), config.getMaxRerankPairs());
        assertEquals(5, config.getDenseTopD());
        assertEquals(20, config.getPerDocL2Cap());
    }

    @Test
    void tokenBudgets_sane() {
        assertEquals(6000, config.getMaxContextTokens());
        assertEquals(32000, config.getModelMaxContext());
        assertEquals(1200, config.getAnswerTokenReserve());
        assertTrue(config.getMaxContextTokens() < config.getModelMaxContext() - config.getAnswerTokenReserve(),
                "maxContextTokens 应小于模型余量，cap 由它决定");
    }

    @Test
    void recallLadder_sane() {
        assertEquals(40, config.getMaxL0Candidates());
        assertEquals(8, config.getDenseTopM());
        assertEquals(5, config.getDenseTopD());
        assertEquals(3, config.getMaxL2Read());
        // step6 取证数 maxL2Read ≤ denseTopM（rerank 池来自 topM L0 的 L2 子节点）
        assertTrue(config.getMaxL2Read() <= config.getDenseTopM());
    }

    @Test
    void abstainAndBoostThresholds() {
        assertEquals(0.5f, config.getAbstainThreshold());
        assertEquals(0.10f, config.getBm25BoostMax());
    }

    @Test
    void memoryConflictDefaults() {
        assertEquals(0.6, RagConfig.MEMORY_BLOCK_SIM_THRESHOLD);
        assertEquals(10, RagConfig.MEMORY_CONFLICT_EXPIRE_MIN);
    }
}
