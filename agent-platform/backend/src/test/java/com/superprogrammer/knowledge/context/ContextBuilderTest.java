package com.superprogrammer.knowledge.context;
import org.junit.jupiter.api.Test; import java.util.*; import static org.junit.jupiter.api.Assertions.*;
class ContextBuilderTest{@Test void removesInvalidAndDuplicateEvidenceAndHonorsTokenCap(){var b=new ContextBuilder();var in=List.of(new ContextBuilder.Item(1L,"a","h",true,true),new ContextBuilder.Item(2L,"a","h",true,true),new ContextBuilder.Item(3L,"forbidden","x",false,true));var out=b.build(in,1);assertEquals(List.of(1L),out.stream().map(ContextBuilder.Item::nodeId).toList());}}
