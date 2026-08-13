package com.superprogrammer.knowledge.context;
import java.util.*;
public class CoverageVerifier{
 public List<String> missing(List<String> required,Set<String> covered){return required.stream().filter(k->!covered.contains(k)).distinct().toList();}
 public int maxRounds(boolean highAccuracy){return highAccuracy?2:1;}
}
