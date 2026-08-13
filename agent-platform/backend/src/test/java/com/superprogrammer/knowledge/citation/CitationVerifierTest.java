package com.superprogrammer.knowledge.citation;
import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class CitationVerifierTest{@Test void rejectsRevokedOldHashAndUnsupportedClaim(){var v=new CitationVerifier();assertTrue(v.verify(new CitationVerifier.Citation(1L,"p3","h",true,true),"事实",text->true));assertFalse(v.verify(new CitationVerifier.Citation(1L,"p3","old",true,false),"事实",text->true));assertFalse(v.verify(new CitationVerifier.Citation(1L,"p3","h",true,true),"事实",text->false));}}
