package com.superprogrammer.knowledge.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReconciliationProperties 默认值锚测（opt-in 默认关，pollMs 10 分钟，batch/decay/kb 默认）。
 */
class ReconciliationPropertiesTest {

    @Test
    void defaults_areOptInAndSane() {
        ReconciliationProperties p = new ReconciliationProperties();
        assertFalse(p.isEnabled(), "默认关（opt-in）");
        assertEquals(600_000L, p.getPollMs(), "默认 10 分钟轮询");
        assertEquals(500, p.getDecayBatch());
        assertEquals(20, p.getKbBatch());
        assertFalse(p.isAutoRepair(), "autoRepair 默认关（claimBatch 不消费 REINDEX）");
    }

    @Test
    void settersBind() {
        ReconciliationProperties p = new ReconciliationProperties();
        p.setEnabled(true);
        p.setPollMs(30_000L);
        p.setDecayBatch(100);
        p.setKbBatch(5);
        p.setAutoRepair(true);
        assertTrue(p.isEnabled());
        assertEquals(30_000L, p.getPollMs());
        assertEquals(100, p.getDecayBatch());
        assertEquals(5, p.getKbBatch());
        assertTrue(p.isAutoRepair());
    }
}
