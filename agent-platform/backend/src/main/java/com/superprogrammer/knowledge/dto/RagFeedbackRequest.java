package com.superprogrammer.knowledge.dto;

public record RagFeedbackRequest(Long knowledgeBaseId, Long evaluationResultId,
                                 String category, String comment) {}
