package com.superprogrammer.knowledge.evaluation;
import org.junit.jupiter.api.Test; import java.util.*; import static org.junit.jupiter.api.Assertions.*;
class ReleaseGateServiceTest{@Test void blocksWhenAnyRequiredMetricMissesThreshold(){var g=new ReleaseGateService(Map.of("recall",.92,"citation",.95));assertTrue(g.evaluate(Map.of("recall",.94,"citation",.96)).passed());assertFalse(g.evaluate(Map.of("recall",.94,"citation",.90)).passed());}}
