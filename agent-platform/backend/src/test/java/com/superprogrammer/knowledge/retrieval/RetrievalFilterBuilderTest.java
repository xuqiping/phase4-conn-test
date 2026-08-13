package com.superprogrammer.knowledge.retrieval;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class RetrievalFilterBuilderTest {
 @Test void requiresTenantAndKbAndBuildsAclVersionPrefilter(){
  RetrievalFilterBuilder b=new RetrievalFilterBuilder();
  assertThrows(IllegalArgumentException.class,()->b.build(null,1L,List.of("user:1"),null));
  var f=b.build(1L,42L,List.of("user:1","team:2"),"9");
  assertTrue(f.json().contains("tenantId")); assertTrue(f.json().contains("knowledgeBaseId"));
  assertTrue(f.json().contains("aclTokens")); assertTrue(f.json().contains("documentVersionId"));
  assertTrue(f.summary().contains("acl=2"));
 }
}
