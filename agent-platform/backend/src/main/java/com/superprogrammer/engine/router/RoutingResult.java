package com.superprogrammer.engine.router;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RoutingResult {
    private List<Long> skillIds;
    private String executionPlan;
}
