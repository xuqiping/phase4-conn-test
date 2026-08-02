package com.superprogrammer.workreport.dto;

import jakarta.validation.constraints.NotNull;

public record GenerateReportRequest(
        @NotNull Long configId
) {
}
