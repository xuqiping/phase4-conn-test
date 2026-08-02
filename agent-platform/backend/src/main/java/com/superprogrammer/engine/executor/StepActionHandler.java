package com.superprogrammer.engine.executor;

import com.superprogrammer.engine.context.ExecutionContext;

public interface StepActionHandler {
    String getActionType();
    StepResult execute(String configJson, ExecutionContext context);
}
