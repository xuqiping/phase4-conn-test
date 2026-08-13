package com.superprogrammer.knowledge.answer;
import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class ConfidenceEvaluatorTest{@Test void returnsSixStateProtocolFromConfiguredThresholds(){var e=new ConfidenceEvaluator(.8,.5);assertEquals("SUPPORTED",e.evaluate(.9,true,false));assertEquals("PARTIAL",e.evaluate(.6,true,false));assertEquals("CONFLICT",e.evaluate(.9,true,true));assertEquals("INSUFFICIENT",e.evaluate(.2,false,false));}}
