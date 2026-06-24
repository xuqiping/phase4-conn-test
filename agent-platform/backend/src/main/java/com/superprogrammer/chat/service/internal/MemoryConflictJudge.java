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
            判断新事实与同信息块已有记忆是否【语义冲突】。
            冲突定义：新事实与某条已有记忆描述的是用户的【同一属性/同一件事】，但给出了【不同且互相矛盾】的值。
            注意：
            - key 名可能不同但语义相同（如 favorite_language / favorite_programming_language / 本命语言 是同一属性；child_name / son_name / daughter_name 都指"孩子的名字/存在"），以【语义】为准，不要因 key 字面不同就判非冲突。
            - 若用户原文含"更正/改/不是/其实/换"等修改措辞，强烈提示这是对旧信息的修改 → 视为冲突。
            - 例：旧"喜欢Java" vs 新"喜欢Python"=冲突；旧"有女儿小红" vs 新"有儿子小明"=冲突（同一"孩子"属性）；旧"已婚" vs 新"喜欢跑步"=非冲突（不同属性）。
            用户原文: %s
            新事实: %s
            已有记忆: %s
            返回JSON数组，每条新事实一个元素: {"factIdx":0基,"conflict":true或false,"conflictingIds":[与之冲突的已有记忆id数组],"askText":"若冲突，中文给用户的提问，如 你之前说喜欢Java，现在说喜欢Python，保留哪条？"}
            无冲突也每条返回（conflict=false, conflictingIds=[]）。
            JSON:""";

    private static final String ROUTER_PROMPT = """
            用户收到一个记忆冲突提问后回复了消息。判断用户想保留哪条。
            冲突提问: "%s"
            候选：A=【之前/原来】的旧信息（提问里"之前提到/原来"指代的那条）；B=【现在/更正】后的新信息（提问里"现在/更正说"指代的那条）。
            用户回复: "%s"
            返回JSON: {"isAnswer":true或false,"keep":"A"|"B"|"BOTH"|"NONE"|"UNCLEAR"}
            规则：keep=A=保留旧弃新；keep=B=保留新弃旧；keep=BOTH=两条都留；keep=NONE=都删；用户回复与冲突无关→isAnswer=false。
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

    /** batch 冲突判定（传 userMessage 保留"更正"等修改信号）。失败返全 false（fail-safe）。 */
    public List<JudgeResult> judge(List<ExtractedFact> facts, List<UserMemory> blockMembers, String userMessage) {
        try {
            String newJson = objectMapper.writeValueAsString(facts.stream()
                    .map(f -> java.util.Map.of("idx", facts.indexOf(f), "key", f.key(), "value", f.value())).toList());
            String memJson = objectMapper.writeValueAsString(blockMembers.stream()
                    .map(m -> java.util.Map.of("id", m.getId(), "key", m.getMemoryKey(), "value", m.getMemoryValue())).toList());
            String json = stripFence(chat(String.format(JUDGE_PROMPT, userMessage == null ? "" : userMessage, newJson, memJson)));
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
