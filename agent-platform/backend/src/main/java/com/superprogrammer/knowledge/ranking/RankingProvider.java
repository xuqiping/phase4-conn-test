package com.superprogrammer.knowledge.ranking;
import com.superprogrammer.knowledge.retrieval.RetrievalCandidate;
import java.util.List;
public interface RankingProvider { List<RankingResult> rank(String query,List<RetrievalCandidate> candidates,String model); String mode(); }
