package com.superprogrammer.knowledge.ranking;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ModelRerankProviderTest {
 @Test void unavailableCapabilityFailsClosed(){var p=new ModelRerankProvider(false,null);assertThrows(IllegalStateException.class,()->p.rank("q",List.of(),"rerank-model"));}
}
