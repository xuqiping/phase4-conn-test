package com.superprogrammer.knowledge.context;
import org.junit.jupiter.api.Test; import java.util.*; import static org.junit.jupiter.api.Assertions.*;
class CoverageVerifierTest{@Test void limitsRoundsAndFindsMissingKeys(){var v=new CoverageVerifier();assertEquals(List.of("B"),v.missing(List.of("A","B"),Set.of("A")));assertEquals(1,v.maxRounds(false));assertEquals(2,v.maxRounds(true));}}
