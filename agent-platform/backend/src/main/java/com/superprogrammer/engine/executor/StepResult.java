package com.superprogrammer.engine.executor;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StepResult {
    private boolean success;
    private String output;
    private String errorMessage;
    private Long duration;

    public static StepResult ok(String output, Long duration) {
        return StepResult.builder().success(true).output(output).duration(duration).build();
    }

    public static StepResult fail(String errorMessage) {
        return StepResult.builder().success(false).errorMessage(errorMessage).build();
    }
}
