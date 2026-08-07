package com.superprogrammer.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.engine.context.ExecutionContext;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmCallHandler implements StepActionHandler {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    @Override
    public String getActionType() {
        return "LLM_CALL";
    }

    @Override
    public StepResult execute(String configJson, ExecutionContext context) {
        long start = System.currentTimeMillis();
        try {
            JsonNode config = objectMapper.readTree(configJson);

            String systemPromptTemplate = config.at("/systemPrompt").asText("");
            String promptTemplate = config.at("/promptTemplate").asText("");
            String model = config.at("/model").asText("doubao-seed-2.0-code");
            String outputKey = config.at("/outputKey").asText("output");
            double temperature = config.at("/temperature").asDouble(0.7);

            String systemPrompt = context.getVariableStore().renderTemplate(systemPromptTemplate);
            String prompt = context.getVariableStore().renderTemplate(promptTemplate);
            List<LlmMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(LlmMessage.builder().role("system").content(systemPrompt).build());
            }
            messages.add(LlmMessage.builder().role("user").content(prompt).build());

            LlmRequest request = LlmRequest.builder()
                    .model(model)
                    .messages(messages)
                    .temperature(temperature)
                    .build();

            var response = llmGateway.chat(request, context.getUserId());
            String output = response.getContent();

            context.getVariableStore().set(outputKey, output);
            context.addMessage("assistant", output);

            long duration = System.currentTimeMillis() - start;
            log.info("LLM_CALL完成, outputKey={}, tokens={}, duration={}ms",
                    outputKey, response.getUsage().getTotalTokens(), duration);

            return StepResult.ok(output, duration);
        } catch (Exception e) {
            log.error("LLM_CALL执行失败", e);
            return StepResult.fail("LLM调用失败: " + e.getMessage());
        }
    }
}
