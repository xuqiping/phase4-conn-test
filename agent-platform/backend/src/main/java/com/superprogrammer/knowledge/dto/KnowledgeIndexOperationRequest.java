package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

public record KnowledgeIndexOperationRequest(@NotBlank String snapshotId, boolean confirmed, boolean dryRun) {}
