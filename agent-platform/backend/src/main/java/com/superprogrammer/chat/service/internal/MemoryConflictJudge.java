package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.chat.entity.UserMemory;
import com.superprogrammer.knowledge.service.RagConfig;
import com.superprogrammer.llm.LlmGateway;
import com.superprogrammer.llm.dto.LlmMessage;
import com.superprogrammer.llm.dto.LlmRequest;
import com.superprogrammer.llm.dto.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆冲突判定器（3 个 LLM 调用：抽取 / batch 判定 / 答复路由）。
 * 沿用现有 regex 解析风格（项目自承 production 换 Jackson，阶段7 完善）。
 * fail-safe：任何失败 → 不冲突/不答（绝不丢事实、不误拦截）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryConflictJudge {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    private static final String EXTRACT_PROMPT = """
            分析以下对话，提取用户的偏好、个人事实或反馈。
            返回JSON数组，每个元素: {"category":"PREFERENCE|FACT|FEEDBACK","key":"英文短键如 favorite_language","value":"值","confidence":0.0-1.0,"block":"该事实所属信息块的中文短名，如 家庭信息/职业/教育/偏好/联系方式/健康/财务"}
            没有可提取信息返回 []
            用户消息: %s
            助手回复: %s
            JSON:""";

    private static final String JUDGE_PROMPT = """
            判断新事实与同信息块已有记忆是否冲突（语义矛盾，如"有女儿"vs"有儿子"、"喜欢Java"vs"喜欢Python"）。
            新事实:
            %s
            已有记忆:
            %s
            返回JSON数组，每条新事实一个元素: {"factIdx":0基序号,"conflict":true或false,"conflictingIds":[已有记忆id数组],"askText":"若冲突，用中文给用户的提问，如 你之前说有个女儿小红，现在说有个儿子小明，保留哪条？"}
            无冲突也每条返回（conflict=false）。
            JSON:""";

    private static final String ROUTER_PROMPT = """
            用户刚收到一个记忆冲突提问: "%s"
            用户最新消息: "%s"
            判断用户这条消息是否在回答该冲突，若是给出决定。
            返回JSON: {"isAnswer":true或false,"decision":"KEEP_NEW|KEEP_OLD|KEEP_BOTH|DISCARD|UNCLEAR"}
            JSON:""";

    /** 抽取事实（含 block 候选）。失败/空返 empty。 */
    public List<ExtractedFact> extract(String userMessage, String assistantResponse) {
        String json = chat(String.format(EXTRACT_PROMPT, userMessage, assistantResponse));
        List<ExtractedFact> out = new ArrayList<>();
        if (json == null || json.isBlank() || json.contains("[]")) return out;
        json = stripFence(json);
        Pattern p = Pattern.compile(
                "\"category\"\\s*:\\s*\"(\\w+)\".*?\"key\"\\s*:\\s*\"([^\"]+)\".*?\"value\"\\s*:\\s*\"([^\"]*)\".*?\"confidence\"\\s*:\\s*([\\d.]+).*?\"block\"\\s*:\\s*\"([^\"]*)\"",
                Pattern.DOTALL);
        Matcher m = p.matcher(json);
        while (m.find()) {
            out.add(new ExtractedFact(m.group(1), m.group(2), m.group(3),
                    new java.math.BigDecimal(m.group(4)), m.group(5)));
        }
        return out;
    }

    /** batch 冲突判定。失败返全 false（fail-safe）。 */
    public List<JudgeResult> judge(List<ExtractedFact> facts, List<UserMemory> blockMembers) {
        try {
            String newJson = objectMapper.writeValueAsString(facts.stream()
                    .map(f -> java.util.Map.of("idx", facts.indexOf(f), "key", f.key(), "value", f.value())).toList());
            String memJson = objectMapper.writeValueAsString(blockMembers.stream()
                    .map(m -> java.util.Map.of("id", m.getId(), "key", m.getMemoryKey(), "value", m.getMemoryValue())).toList());
            String json = stripFence(chat(String.format(JUDGE_PROMPT, newJson, memJson)));
            List<JudgeResult> out = new ArrayList<>();
            Pattern p = Pattern.compile(
                    "\"factIdx\"\\s*:\\s*(\\d+).*?\"conflict\"\\s*:\\s*(true|false)(?:.*?\"conflictingIds\"\\s*:\\s*\\[([^\\]]*)\\])?(?:.*?\"askText\"\\s*:\\s*\"([^\"]*)\")?",
                    Pattern.DOTALL);
            Matcher m = p.matcher(json == null ? "" : json);
            while (m.find()) {
                int idx = Integer.parseInt(m.group(1));
                boolean conflict = Boolean.parseBoolean(m.group(2));
                List<Long> ids = parseIds(m.group(3));
                out.add(new JudgeResult(idx, conflict, ids, m.group(4)));
            }
            if (out.size() != facts.size()) {
                log.warn("judge 返回条数{}!=事实{}，fail-safe 全无冲突", out.size(), facts.size());
                return facts.stream().map(f -> new JudgeResult(facts.indexOf(f), false, List.of(), null)).toList();
            }
            return out;
        } catch (Exception e) {
            log.warn("冲突判定失败 fail-safe: {}", e.getMessage());
            return facts.stream().map(f -> new JudgeResult(facts.indexOf(f), false, List.of(), null)).toList();
        }
    }

    /** 答复路由原始 JSON（ChatSessionService 解析 isAnswer/decision）。 */
    public String route(String askText, String userMessage) {
        return stripFence(chat(String.format(ROUTER_PROMPT, askText, userMessage)));
    }

    private String chat(String prompt) {
        try {
            LlmResponse resp = llmGateway.chat(LlmRequest.builder()
                    .model(RagConfig.MEMORY_JUDGE_MODEL)
                    .messages(List.of(LlmMessage.builder().role("user").content(prompt).build()))
                    .temperature(0.3).maxTokens(800).build());
            return resp.getContent();
        } catch (Exception e) {
            log.warn("LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private static String stripFence(String json) {
        if (json == null) return null;
        json = json.trim();
        if (json.startsWith("```")) {
            int s = json.indexOf('\n') + 1;
            int e = json.lastIndexOf("```");
            if (e > s) json = json.substring(s, e).trim();
        }
        return json;
    }

    private static List<Long> parseIds(String arr) {
        List<Long> ids = new ArrayList<>();
        if (arr == null || arr.isBlank()) return ids;
        Matcher m = Pattern.compile("\\d+").matcher(arr);
        while (m.find()) ids.add(Long.parseLong(m.group()));
        return ids;
    }
}
