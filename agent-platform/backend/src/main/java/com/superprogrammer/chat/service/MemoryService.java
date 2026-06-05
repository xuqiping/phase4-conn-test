package com.superprogrammer.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.chat.mapper.UserMemoryMapper;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final UserMemoryMapper memoryMapper;
    private final LlmGateway llmGateway;

    private static final String EXTRACTION_PROMPT = """
            分析以下对话，提取用户的偏好、个人事实或反馈。
            返回JSON数组，每个元素包含: category(PREFERENCE/FACT/FEEDBACK), key, value, confidence(0-1)
            如果没有可提取的信息，返回空数组 []

            用户消息: %s
            助手回复: %s

            JSON:""";

    public String buildMemoryContext(Long userId) {
        LambdaQueryWrapper<UserMemory> wrapper = new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .ge(UserMemory::getConfidence, new BigDecimal("0.5"))
                .orderByDesc(UserMemory::getUpdatedAt);
        List<UserMemory> memories = memoryMapper.selectList(wrapper);
        if (memories.isEmpty()) return null;

        return memories.stream()
                .map(m -> "[" + m.getCategory() + "] " + m.getMemoryKey() + ": " + m.getMemoryValue())
                .collect(Collectors.joining("\n"));
    }

    @Async
    public void extractMemoriesAsync(Long userId, String userMessage, String assistantResponse) {
        try {
            LlmRequest request = LlmRequest.builder()
                    .model("doubao-seed-2.0-code")
                    .messages(List.of(
                            LlmMessage.builder().role("user").content(
                                    String.format(EXTRACTION_PROMPT, userMessage, assistantResponse)).build()))
                    .temperature(0.3)
                    .maxTokens(500)
                    .build();

            LlmResponse response = llmGateway.chat(request);
            parseAndSaveMemories(userId, response.getContent());
        } catch (Exception e) {
            log.warn("记忆提取失败, userId={}: {}", userId, e.getMessage());
        }
    }

    private void parseAndSaveMemories(Long userId, String json) {
        // Simple parsing - production would use Jackson
        // For now, skip malformed responses gracefully
        if (json == null || json.isBlank() || json.contains("[]")) return;

        try {
            json = json.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n') + 1;
                int end = json.lastIndexOf("```");
                json = json.substring(start, end).trim();
            }

            // Use simple regex-based extraction for key=value pairs
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "\"category\"\\s*:\\s*\"(\\w+)\".*?\"key\"\\s*:\\s*\"([^\"]+)\".*?\"value\"\\s*:\\s*\"([^\"]+)\".*?\"confidence\"\\s*:\\s*([\\d.]+)");
            java.util.regex.Matcher matcher = pattern.matcher(json);

            while (matcher.find()) {
                String category = matcher.group(1);
                String key = matcher.group(2);
                String value = matcher.group(3);
                BigDecimal confidence = new BigDecimal(matcher.group(4));

                upsertMemory(userId, category, key, value, "INFERRED", confidence);
            }
        } catch (Exception e) {
            log.warn("记忆解析失败: {}", e.getMessage());
        }
    }

    private void upsertMemory(Long userId, String category, String key, String value,
                              String source, BigDecimal confidence) {
        LambdaQueryWrapper<UserMemory> wrapper = new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getMemoryKey, key);
        UserMemory existing = memoryMapper.selectOne(wrapper);

        if (existing != null) {
            existing.setMemoryValue(value);
            existing.setConfidence(confidence);
            existing.setCategory(category);
            memoryMapper.updateById(existing);
        } else {
            UserMemory memory = new UserMemory();
            memory.setUserId(userId);
            memory.setCategory(category);
            memory.setMemoryKey(key);
            memory.setMemoryValue(value);
            memory.setSource(source);
            memory.setConfidence(confidence);
            memoryMapper.insert(memory);
        }
    }
}
