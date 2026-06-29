package com.superprogrammer.chat.service.internal;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 记忆冲突判定器（3 个 LLM 调用：抽取 / batch 判定 / 答复路由）。
 *
 * 准确率调优（2026-06-22）：regex 解析 → Jackson readTree（容错字段顺序/转义引号/值含标点，
 * 杀掉旧 regex 漏匹配导致的 count-mismatch fail-safe 静默漏判）；temperature 0.3→0.0（分类确定性）；
 * prompt 强化（key 归一指引 + few-shot + 纯 JSON 契约）。
 *
 * fail-safe 原则不变：任何失败 → 不冲突/不答（绝不丢事实、不误拦截）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryConflictJudge {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    private static final Set<String> VALID_CATEGORIES = Set.of("PREFERENCE", "FACT", "FEEDBACK");

    /** 分类/判定/路由统一低温（确定性优先，抽取也走低温以稳定 key 命名）。 */
    private static final double JUDGE_TEMPERATURE = 0.0;

    private static final String EXTRACT_PROMPT = """
            你是记忆抽取器。分析以下对话，提取用户的偏好、个人事实或反馈。

            输出契约（必须严格遵守）：
            - 只输出一个 JSON 数组，不要任何 markdown 围栏、注释或解释文字。
            - 每个元素: {"category":"PREFERENCE|FACT|FEEDBACK","key":"英文短键","key_zh":"中文标签","value":"值","confidence":0.0-1.0,"block":"中文短块名","entities":["召回词",...]}
            - 没有可提取信息时输出 []

            key_zh（重要，决定中文召回 + 前端显示）：
            - key 的中文主标签，1-6 个中文字，用用户视角的自然称呼。
            - 例：key=child_name → key_zh="女儿"；key=home_city → key_zh="住址"（或"居住地"）；
              key=favorite_language → key_zh="编程语言"；key=occupation → key_zh="职业"。
            - 纯英文 key 必须给中文标签；抽不出 → null。

            entities（重要，决定关键词召回准确率）= 召回词袋，四类词都要：
            1. key_zh 中文标签（必含，如"女儿"）；
            2. 同义变体 1-3 个（角色/称谓/类别词的近义说法，如 女儿→孩子/小孩/闺女；公司→单位/企业；老婆→妻子/爱人）；
            3. value 里的专有名词（人名/地名/品牌，原文字面词，如"啊闪""北京""Java"）；
            4. **所属类别泛称/上位词**（决定泛问召回，如 query「带家人出去玩」能召回配偶/孩子/宠物。**至少 5 个，能多则多——概念越宽泛（家庭/工作/居住等高频类别）越往 8-10 靠，上限 10**；只有该类别真实存在的上位词确实不足 5 个时才减少，勿无故压到 1-2 个。下面每类列了多个候选，挑贴切的都补，**不止于下列——可自行补充该类别其他常见 2+ 字上位词**；事实横跨多类别时叠加。全用 2 字以上词，**严禁单字**——单字不进 2-gram 分词召不回、且高频命中过宽成噪声）：
               - 配偶/妻子/老公/孩子/儿子/女儿/父母/亲属/宠物 → 家人 / 家庭 / 亲属 / 家属 / 亲人 / 眷属 / 家眷 / 族亲 / 至亲；
               - 公司/职位/职级/单位/行业/薪水/上班 → 工作 / 职业 / 职场 / 事业 / 岗位 / 职务 / 就业 / 差事 / 营生；
               - 过敏/病史/用药/身高/体重/体检/疾病 → 健康 / 身体 / 体质 / 医疗 / 病史 / 体能 / 体征 / 体格 / 状况；
               - 住址/城市/小区/籍贯/搬家/租住/老家 → 居住地 / 住址 / 居住 / 住所 / 住处 / 定居 / 落户 / 居所 / 寓所；
               - 学校/专业/学历/院校/考研/学位/老师 → 教育 / 学历 / 学业 / 学习 / 求学 / 深造 / 在校 / 读书 / 进修；
               - 爱好/喜好/口味/音乐/运动/电影/书籍/游戏 → 偏好 / 爱好 / 喜好 / 兴趣 / 口味 / 品味 / 消遣 / 嗜好 / 偏爱；
               - 电话/邮箱/微信/QQ/地址/联系方式 → 联系方式 / 联系 / 通讯 / 联络 / 通联 / 账号 / 渠道 / 通讯录 / 社交；
               - 收入/工资/存款/理财/资产/房贷/股票 → 财务 / 收入 / 资产 / 财富 / 经济 / 薪金 / 钱财 / 收支 / 进项；
               - 名字/姓名/年龄/生日/性别/属相/星座 → 基本信息 / 个人 / 档案 / 资料 / 身份 / 简介 / 概况 / 履历 / 本人。
            - **角色词必含**：value 只有专有名（如 value="啊闪"）时，必须从 key 语义补角色词 + 变体
              （child_name → 补"女儿"+"孩子"，否则 query「带女儿去玩」召回不到这条）。
            - 每词 ≤8 字符，共 ≤20 个，去重。
            - 例：value="啊闪" key=child_name key_zh="女儿" → entities:["女儿","孩子","小孩","家人","啊闪"]；
              value="住在北京" key=home_city key_zh="住址" → entities:["住址","北京","居住地"]；
              value="用Java" key=favorite_language key_zh="编程语言" → entities:["编程语言","Java","偏好","爱好"]。
            - 抽不出 → []。

            key 命名（重要，决定去重/冲突识别准确率）：
            - 用稳定通用的英文蛇形短键，只含小写字母/数字/下划线，≤40 字符。
            - 同一概念只用一个 key；以【语义】为准，不因字面不同就另造变体。
            - 【复用优先，严格遵守】该用户已存在的 key 列表：%s
              抽取的属性若与列表中任一 key 语义相同（同一属性/同一件事，如 favorite_sport 与 hobby、child_grade 与 child_education_stage 视为同一属性），必须直接复用列表里的那个 key，不得另造新 key。
              仅当确属列表之外的全新属性时，才用一个通用规范新 key。
            - 列表为空（新用户）时，按通用常识给规范短键（如 favorite_language / name / age / occupation / spouse_name / child_name / home_city / dietary_preference）。

            block（事实归属的信息块，用于聚类归块）：
            - 用中文短名，固定优先复用：家庭信息 / 职业 / 教育 / 偏好 / 联系方式 / 健康 / 财务 / 基本信息；都不贴再自造短名。

            示例：
            用户: "我用 Java 写后端，最近在学 Python"
            助手: "好的，记下了。"
            [{"category":"FACT","key":"occupation","value":"后端工程师(Java)","confidence":0.85,"block":"职业"},{"category":"PREFERENCE","key":"learning_language","value":"Python","confidence":0.7,"block":"偏好"}]

            用户消息: %s
            助手回复: %s
            JSON:""";

    private static final String JUDGE_PROMPT = """
            你是记忆冲突判定器。判断【新事实】与【同信息块已有记忆】是否语义冲突。

            冲突定义：新事实与某条已有记忆描述的是用户的【同一属性/同一件事】，但给出了【不同且互相矛盾】的值。
            判定要点：
            - 以【语义】为准，不要因 key 字面不同就判非冲突（favorite_language / favorite_programming_language / 本命语言 是同一属性；child_name / son_name / daughter_name 都指"孩子的名字/存在"）。
            - 同一属性的不同值 = 冲突（旧"喜欢Java" vs 新"喜欢Python"；旧"有女儿小红" vs 新"有儿子小明"——同属"孩子"属性）。
            - 不同属性 = 非冲突（旧"已婚" vs 新"喜欢跑步"）。
            - 互补/补充 = 非冲突（旧"会用Java" vs 新"也会用Python"，除非新值明确否定旧值）。
            - 用户原文含"更正/改/不是/其实/换成/记错了"等修改措辞 → 强烈提示修改旧信息 → 倾向冲突。

            输出契约（必须严格遵守）：
            - 只输出一个 JSON 数组，每条新事实一个元素，顺序与新事实数组一致。
            - 每个元素: {"factIdx":0基下标,"conflict":true或false,"conflictingIds":[与之冲突的已有记忆id数组,无冲突为空],"askText":"若冲突,给用户的中文提问;无冲突为空串"}
            - 无冲突的元素也要返回（conflict=false, conflictingIds=[], askText="")。
            - askText 里不要出现双引号、不要换行；用「」代替内嵌引号。
            - 不要 markdown 围栏或解释文字。

            用户原文: %s
            新事实: %s
            已有记忆: %s
            JSON:""";

    private static final String ROUTER_PROMPT = """
            用户刚收到一个记忆冲突提问，随后回复了一条消息。判断这条回复是不是在回答该冲突，并给出保留决定。

            冲突提问: "%s"
            候选标签：A=【之前/原来】的旧信息（提问里"之前/原来/以前"指代的那条）；B=【现在/更正/改】后的新信息（提问里"现在/更正说/改成"指代的那条）。
            用户回复: "%s"

            输出契约（必须严格遵守）：
            - 只输出一个 JSON 对象，不要 markdown 围栏或解释。
            - {"isAnswer":true或false,"keep":"A"|"B"|"BOTH"|"NONE"|"UNCLEAR"}
            - keep=A 保留旧弃新；keep=B 保留新弃旧；keep=BOTH 两条都留；keep=NONE 都删。
            - 用户回复与冲突提问无关（聊别的、问别的）→ isAnswer=false, keep="UNCLEAR"。
            - 用户回复意图不清（没明确选）→ isAnswer=false, keep="UNCLEAR"。
            JSON:""";

    /** 三维合并 key 筛（V38 优化）：keys + keys_zh + blocks 一次 LLM 出，砍掉原先 key/block 两次调用。
     *  召回优先（宁多选）。三路径共用（fullContext 超阈值 / hybrid 0命中兜底 / LLM_KEY 精排）。 */
    private static final String SELECT_KEYS_BLOCKS_PROMPT = """
            你是用户记忆检索助手。一次性完成三维度筛选：
            ① 从【用户已有记忆】挑与问题相关的英文 memory_key；
            ② 从中挑相关的中文标签 key_zh；
            ③ 从【信息块列表】挑相关的 block_label。
            三维独立判断，召回优先（宁多选，仅明显无关才不选，不确定倾向选择）。

            判定要点：
            - 相关 = 能帮回答/影响建议/提供该用户专属上下文。
            - 例：问"带家人出去玩" → keys 含 spouse_name/child_name/pet_dog_name；keys_zh 含 妻子/女儿/宠物；blocks 含 家庭信息。
            - 例：问"今晚吃什么" → keys 含 dietary_preference；keys_zh 含 饮食偏好/忌口；blocks 含 偏好/健康。
            - key_zh 是 key 的中文标签（妻子/女儿/住址…），跟 key 配对出现；同一行相关时 keys 和 keys_zh 里对应项都要选。

            用户问题: %s
            用户已有记忆（中文标签(英文键): 值）:
            %s
            信息块列表:
            %s

            输出契约（必须严格遵守）：
            - 只输出一个 JSON 对象，不要 markdown 围栏或解释：
              {"keys":["相关英文键",...], "keys_zh":["相关中文标签",...], "blocks":["相关信息块",...]}
            - 三字段都原样复制上面列表里的值（keys 取英文键、keys_zh 取中文标签、blocks 取信息块名），不要改写。
            - 某维无相关 → 该字段输出 []。
            JSON:""";

    /** 老数据回填：一批记忆批量抽中文标签 key_zh + 召回词袋 entities（≤20条/次）。idx = 记忆 id。 */
    private static final String BATCH_ENTITIES_PROMPT = """
            你是记忆实体抽取器。为下列每条【用户记忆】抽取中文标签 key_zh + 召回词袋 entities，
            用于检索时把"提到同一实体/角色"的不同问题关联起来。

            规则：
            - key_zh = key 的中文主标签（1-6 中文字），据英文 key 语义推断。
              例：key=child_name → key_zh="女儿"；key=home_city → key_zh="住址"；key=favorite_language → key_zh="编程语言"。抽不出 → null。
            - entities = 召回词袋，四类都要：① key_zh 标签（必含）；② 同义变体 1-3（女儿→孩子/小孩/闺女；公司→单位/企业）；
              ③ value 里的专有名词（原文字面词，如"啊闪""北京""Java"）；
              ④ **所属类别泛称/上位词**（决定泛问召回。**至少 5 个，能多则多——概念越宽泛越往 8-10 靠，上限 10**；只有该类别真实上位词确实不足 5 个时才减少，勿无故压到 1-2 个；**不止于下列候选，可自行补该类别其他常见 2+ 字上位词**；全用 2 字以上词**严禁单字**——单字不进 2-gram 分词召不回、且高频命中过宽成噪声）：
              配偶/孩子/父母/宠物→家人/家庭/亲属/家属/亲人/眷属/家眷/族亲/至亲；公司/职位/单位→工作/职业/职场/事业/岗位/职务/就业/差事/营生；过敏/病史/身高体重→健康/身体/体质/医疗/病史/体能/体征/体格/状况；
              住址/城市/搬家→居住地/住址/居住/住所/住处/定居/落户/居所/寓所；学校/专业/学历→教育/学历/学业/学习/求学/深造/在校/读书/进修；爱好/口味/运动→偏好/爱好/喜好/兴趣/口味/品味/消遣/嗜好/偏爱；
              电话/邮箱/微信→联系方式/联系/通讯/联络/通联/账号/渠道/通讯录/社交；收入/工资/房贷→财务/收入/资产/财富/经济/薪金/钱财/收支/进项；名字/年龄/生日→基本信息/个人/档案/资料/身份/简介/概况/履历/本人。
            - **角色词必含**：value 只有专有名（如 value="啊闪"）时，必须从 key 语义补角色词 + 变体。
            - 每词 ≤8 字符，共 ≤20 个，去重。抽不出 entities → []。
            - 例：value="啊闪" key=child_name → key_zh="女儿", entities:["女儿","孩子","小孩","家人","啊闪"]；
              value="住在北京" key=home_city → key_zh="住址", entities:["住址","北京","居住地"]。

            记忆列表（格式 idx|key|value）:
            %s

            输出契约（必须严格遵守）：
            - 只输出一个 JSON 数组，每个元素 {"idx":记忆id,"key_zh":"中文标签或null","entities":["词",...]}，顺序与输入一致。
            - 不要 markdown 围栏或解释文字。
            JSON:""";

    /** 抽取事实（含 block 候选）。
     * @param existingKeys 该用户已存在的 memory_key 列表，注入 prompt 做 key 复用白名单（通用语义归一，取代手写 alias 表）。null/empty 视为新用户。 */
    public List<ExtractedFact> extract(String userMessage, String assistantResponse, List<String> existingKeys) {
        String keysDisplay = (existingKeys == null || existingKeys.isEmpty())
                ? "（无，新用户）"
                : String.join(" / ", existingKeys);
        String json = chat(String.format(EXTRACT_PROMPT, keysDisplay, userMessage, assistantResponse));
        log.info("extract raw返回={}", truncate(json));
        if (json == null || json.isBlank()) return List.of();
        JsonNode root = parseJson(stripFence(json));
        if (root == null || !root.isArray()) {
            log.warn("抽取返回非 JSON 数组: {}", truncate(json));
            return List.of();
        }
        List<ExtractedFact> out = new ArrayList<>();
        for (JsonNode el : root) {
            ExtractedFact f = readFact(el);
            if (f != null) out.add(f);
        }
        return out;
    }

    /** batch 冲突判定（传 userMessage 保留"更正"等修改信号）。失败/解析失败返全 false（fail-safe）。 */
    public List<JudgeResult> judge(List<ExtractedFact> facts, List<UserMemory> blockMembers, String userMessage) {
        if (facts == null || facts.isEmpty()) return List.of();
        try {
            String newJson = objectMapper.writeValueAsString(facts.stream()
                    .map(f -> java.util.Map.of("idx", facts.indexOf(f), "key", f.key(), "value", f.value())).toList());
            String memJson = objectMapper.writeValueAsString(blockMembers == null ? List.of() : blockMembers.stream()
                    .map(m -> java.util.Map.of("id", m.getId(), "key", m.getMemoryKey(), "value", m.getMemoryValue())).toList());
            JsonNode root = parseJson(stripFence(chat(String.format(JUDGE_PROMPT,
                    userMessage == null ? "" : userMessage, newJson, memJson))));
            // 按 factIdx 收集；缺失的事实默认无冲突（fail-safe，但不再静默全丢）。
            java.util.Map<Integer, JudgeResult> byIdx = new java.util.HashMap<>();
            if (root != null && root.isArray()) {
                for (JsonNode el : root) {
                    JudgeResult jr = readJudge(el);
                    if (jr != null) byIdx.put(jr.factIdx(), jr);
                }
            } else {
                log.warn("judge 返回非 JSON 数组, fail-safe 全无冲突: {}", root == null ? "(null)" : truncate(root.toString()));
            }
            List<JudgeResult> out = new ArrayList<>();
            for (int i = 0; i < facts.size(); i++) {
                out.add(byIdx.getOrDefault(i, new JudgeResult(i, false, List.of(), null)));
            }
            return out;
        } catch (Exception e) {
            log.warn("冲突判定失败 fail-safe: {}", e.getMessage());
            return facts.stream().map(f -> new JudgeResult(facts.indexOf(f), false, List.of(), null)).toList();
        }
    }

    /** 答复路由：结构化判定（取代旧 string-contains）。失败返 isAnswer=false。 */
    public RouteResult route(String askText, String userMessage) {
        String raw = chat(String.format(ROUTER_PROMPT, askText == null ? "" : askText, userMessage == null ? "" : userMessage));
        JsonNode root = parseJson(stripFence(raw));
        if (root == null || !root.isObject()) {
            log.warn("route 返回非 JSON 对象, fail-safe: {}", raw == null ? "(null)" : truncate(raw));
            return new RouteResult(false, "UNCLEAR");
        }
        boolean isAnswer = root.path("isAnswer").asBoolean(false);
        String keep = root.path("keep").asText("UNCLEAR");
        if (!List.of("A", "B", "BOTH", "NONE", "UNCLEAR").contains(keep)) keep = "UNCLEAR";
        return new RouteResult(isAnswer, isAnswer ? keep : "UNCLEAR");
    }

    /** 批量回填（V31/V32 老数据迁移）：为一批老记忆补抽中文标签 key_zh + 召回词袋 entities。
     *  batch LLM 调用（≤20条/次），返回 memoryId → BackfillRow 映射；失败 → 空 map。
     *  一次性 admin 触发，幂等可重跑（空 entities/key_zh 由调用方落 "[]"/"" 标记已处理）。 */
    public java.util.Map<Long, BackfillRow> batchExtractEntities(List<UserMemory> rows) {
        if (rows == null || rows.isEmpty()) return java.util.Map.of();
        try {
            String body = rows.stream()
                    .map(m -> m.getId() + "|" + m.getMemoryKey() + "|" + m.getMemoryValue())
                    .collect(java.util.stream.Collectors.joining("\n"));
            String raw = chat(String.format(BATCH_ENTITIES_PROMPT, body));
            JsonNode root = parseJson(stripFence(raw));
            java.util.Map<Long, BackfillRow> out = new java.util.HashMap<>();
            if (root == null || !root.isArray()) {
                log.warn("batchExtractEntities 返回非 JSON 数组, fail-safe 空: {}", raw == null ? "(null)" : truncate(raw));
                return out;
            }
            for (JsonNode el : root) {
                long id = el.path("idx").asLong(-1L);
                if (id < 0) continue;
                String keyZh = textOrDefault(el, "key_zh", null);
                if (keyZh != null) keyZh = keyZh.isBlank() ? null : keyZh.trim();
                out.put(id, new BackfillRow(readEntities(el.get("entities")), keyZh));
            }
            return out;
        } catch (Exception e) {
            log.warn("batchExtractEntities 失败 fail-safe 空: {}", e.getMessage());
            return java.util.Map.of();
        }
    }

    /** 回填单条结果：召回词袋 entities + 中文标签 keyZh。 */
    public record BackfillRow(List<String> entities, String keyZh) {}

    /** 三维筛结果（V38 合并）：相关英文 key 集合 + 相关中文 key_zh 集合 + 相关 block 集合。
     *  调用方做三维 AND 交集装配。空集合 = LLM 判该维无相关（参与 AND，可能致结果空）。 */
    public record RelevantDims(Set<String> keys, Set<String> keysZh, Set<String> blocks) {}

    /** 三维合并一次 LLM（V38 优化）：keys + keys_zh + blocks 一次出，取代原 key/block 两次调用。
     *  召回优先（宁多选）。distinctByKey 已按 memory_key 去重（每行带 key_zh/value 供展示+校验）。
     *  返回三维集合（均原样复制、过白名单校验）；query 空 / 入参空 / LLM 失败 / 解析失败 → null（上层降级不注入）。 */
    public RelevantDims selectRelevantKeysBlocks(String query, List<UserMemory> distinctByKey, List<String> distinctBlocks) {
        if (query == null || query.isBlank() || distinctByKey == null || distinctByKey.isEmpty()) return null;
        try {
            String memList = distinctByKey.stream()
                    .map(m -> "- " + zhKeyDisplay(m) + ": " + m.getMemoryValue())
                    .collect(java.util.stream.Collectors.joining("\n"));
            String blockList = (distinctBlocks == null || distinctBlocks.isEmpty()) ? "- "
                    : distinctBlocks.stream().map(b -> "- " + b).collect(java.util.stream.Collectors.joining("\n"));
            String raw = chat(String.format(SELECT_KEYS_BLOCKS_PROMPT, query, memList, blockList));
            JsonNode root = parseJson(stripFence(raw));
            if (root == null || !root.isObject()) {
                log.warn("selectRelevantKeysBlocks 返回非 JSON 对象, fail-safe null: {}", raw == null ? "(null)" : truncate(raw));
                return null;
            }
            java.util.Set<String> validKeys = distinctByKey.stream()
                    .map(UserMemory::getMemoryKey).collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> validZh = distinctByKey.stream()
                    .map(UserMemory::getMemoryKeyZh)
                    .filter(s -> s != null && !s.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> validBlocks = distinctBlocks == null ? java.util.Collections.emptySet()
                    : new java.util.HashSet<>(distinctBlocks);
            return new RelevantDims(
                    pickValidStrs(root.get("keys"), validKeys),
                    pickValidStrs(root.get("keys_zh"), validZh),
                    pickValidStrs(root.get("blocks"), validBlocks));
        } catch (Exception e) {
            log.warn("selectRelevantKeysBlocks 失败 fail-safe null: {}", e.getMessage());
            return null;
        }
    }

    /** key_zh(key) 展示串：有中文标签 → 妻子(spouse_name)；无 → 仅英文键。供 prompt 列表展示。 */
    private static String zhKeyDisplay(UserMemory m) {
        String zh = m.getMemoryKeyZh();
        String key = m.getMemoryKey();
        return (zh == null || zh.isBlank()) ? key : zh + "(" + key + ")";
    }

    /** JSON 数组节点 → 过白名单的字符串集合（原样复制 + 去重 + 保序）。null/非数组/空白名单 → 空集。 */
    private static Set<String> pickValidStrs(JsonNode arr, java.util.Set<String> valid) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (arr == null || !arr.isArray() || valid == null || valid.isEmpty()) return out;
        for (JsonNode n : arr) {
            if (n == null) continue;
            String s = n.asText();
            if (s != null && valid.contains(s)) out.add(s);
        }
        return out;
    }

    // ---- Jackson 解析 helpers ----

    private ExtractedFact readFact(JsonNode el) {
        if (el == null || !el.isObject()) return null;
        String category = textOrDefault(el, "category", "FACT");
        if (!VALID_CATEGORIES.contains(category)) category = "FACT";
        String key = textOrDefault(el, "key", null);
        if (key == null || key.isBlank()) return null;
        String value = textOrDefault(el, "value", "");
        BigDecimal confidence = parseConfidence(el.get("confidence"));
        String block = textOrDefault(el, "block", null);
        String keyZh = textOrDefault(el, "key_zh", null);
        if (keyZh != null) keyZh = keyZh.isBlank() ? null : keyZh.trim();
        List<String> entities = readEntities(el.get("entities"));
        return new ExtractedFact(category, key.trim(), keyZh, value, confidence, block, entities);
    }

    /** 解析 entities 数组（容忍缺字段/非数组/空）。去空白去重，上限 10 个（标签+变体+专名）。 */
    private List<String> readEntities(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode e : node) {
            if (e == null) continue;
            String s = e.asText();
            if (s == null) continue;
            s = s.trim();
            if (s.isBlank() || s.length() > 8) continue;
            if (seen.add(s)) out.add(s);
            if (out.size() >= 20) break;
        }
        return out;
    }

    private JudgeResult readJudge(JsonNode el) {
        if (el == null || !el.isObject()) return null;
        int idx = el.path("factIdx").asInt(0);
        boolean conflict = el.path("conflict").asBoolean(false);
        List<Long> ids = new ArrayList<>();
        JsonNode arr = el.get("conflictingIds");
        if (arr != null && arr.isArray()) {
            for (JsonNode idNode : arr) {
                if (idNode.canConvertToLong()) ids.add(idNode.asLong());
            }
        }
        String askText = textOrDefault(el, "askText", null);
        if (askText != null) askText = askText.isBlank() ? null : askText.trim();
        return new JudgeResult(idx, conflict, ids, askText);
    }

    private static String textOrDefault(JsonNode parent, String field, String def) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) return def;
        String s = n.asText();
        return s == null ? def : s;
    }

    private static BigDecimal parseConfidence(JsonNode n) {
        if (n == null || n.isNull()) return BigDecimal.ONE;
        try {
            double d = n.asDouble(1.0);
            if (d < 0) d = 0;
            if (d > 1) d = 1;
            return new BigDecimal(String.format(java.util.Locale.US, "%.2f", d));
        } catch (Exception e) {
            return BigDecimal.ONE;
        }
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", truncate(json));
            return null;
        }
    }

    private String chat(String prompt) {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                LlmResponse resp = llmGateway.chat(LlmRequest.builder()
                        .model(RagConfig.MEMORY_JUDGE_MODEL)
                        .messages(List.of(LlmMessage.builder().role("user").content(prompt).build()))
                        .temperature(JUDGE_TEMPERATURE).maxTokens(800).build());
                String content = resp.getContent();
                if (content != null && !content.isBlank()) return content;
                log.warn("LLM 返回空(第{}/3次) prompt.len={}", attempt, prompt.length());
            } catch (Exception e) {
                last = e;
                log.warn("LLM 调用异常(第{}/3次): {}", attempt, e.getMessage());
            }
        }
        if (last != null) log.warn("LLM 调用 3 次均失败: {}", last.getMessage());
        return null;
    }

    private static String stripFence(String json) {
        if (json == null) return null;
        json = json.trim();
        if (json.startsWith("```")) {
            int s = json.indexOf('\n') + 1;
            int e = json.lastIndexOf("```");
            if (e > s) json = json.substring(s, e).trim();
            else if (s > 0) json = json.substring(s).trim();
        }
        // LLM 偶尔在 JSON 前后塞解释文字，尝试截到首个 [ / { 到末尾匹配。
        int arrStart = json.indexOf('[');
        int objStart = json.indexOf('{');
        int start = -1;
        if (arrStart >= 0 && (objStart < 0 || arrStart < objStart)) start = arrStart;
        else if (objStart >= 0) start = objStart;
        if (start > 0) json = json.substring(start);
        return json.trim();
    }

    private static String truncate(String s) {
        if (s == null) return "(null)";
        return s.length() <= 160 ? s : s.substring(0, 160) + "...";
    }
}
