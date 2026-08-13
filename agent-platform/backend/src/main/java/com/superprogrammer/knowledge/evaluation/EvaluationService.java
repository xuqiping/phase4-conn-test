package com.superprogrammer.knowledge.evaluation;
import org.springframework.stereotype.Service;
@Service public class EvaluationService{private final RagMetricsCalculator metrics=new RagMetricsCalculator();public RagMetricsCalculator calculator(){return metrics;}}
