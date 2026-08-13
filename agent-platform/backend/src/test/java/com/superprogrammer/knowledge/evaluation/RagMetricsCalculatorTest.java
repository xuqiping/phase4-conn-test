package com.superprogrammer.knowledge.evaluation;
import org.junit.jupiter.api.Test; import java.util.*; import static org.junit.jupiter.api.Assertions.*;
class RagMetricsCalculatorTest{@Test void matchesHandCalculatedRecallMrrAndNdcg(){var m=new RagMetricsCalculator().calculate(List.of("a","x","b"),Set.of("a","b"),3);assertEquals(1,m.recall(),1e-9);assertEquals(1,m.mrr(),1e-9);assertTrue(m.ndcg()>0&&m.ndcg()<=1);}}
