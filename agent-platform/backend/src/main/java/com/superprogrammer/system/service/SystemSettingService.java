package com.superprogrammer.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.superprogrammer.system.dto.AuthSettingsVO;
import com.superprogrammer.system.entity.SystemSetting;
import com.superprogrammer.system.mapper.SystemSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class SystemSettingService {
    public static final String ACCESS_TOKEN_EXPIRATION_MS = "auth.access_token_expiration_ms";
    /** RAG/记忆模式总开关（false=opt-in）。4 层优先级：session>agent/workflow>global。 */
    public static final String RAG_MEMORY_ENABLED = "rag.memory.enabled";
    /** 记忆处理模式：ASYNC=全异步(不卡顿,冲突走面板) / HYBRID=同步(即时冲突追问 askText)。 */
    public static final String RAG_MEMORY_PROCESS_MODE = "rag.memory.process-mode";
    /** 记忆检索模式：LLM_FULL_CONTEXT=全量灌入(默认) / EMBEDDING_VECTOR=向量 top-K 真检索。 */
    public static final String RAG_MEMORY_RETRIEVAL_MODE = "rag.memory.retrieval-mode";
    /** 记忆标签语言：EN=英文 key(默认,向后兼容) / ZH=中文 key_zh(空回退英文)。控制注入上下文用哪个 key 展示。 */
    public static final String RAG_MEMORY_KEY_LANGUAGE = "rag.memory.key-language";
    /** 全量模式记忆阈值：记忆条数 > 阈值时改"先加载 key→LLM 选相关→再加载 value"两阶段。
     *  默认 20；0=禁用(始终全量)。仅作用于 LLM_FULL_CONTEXT 模式。 */
    public static final String RAG_MEMORY_FULL_CONTEXT_THRESHOLD = "rag.memory.full-context-threshold";
    /** VECTOR_KEYWORD 关键词召回 per-block_label 阈值：同一信息块命中 > 阈值时，优先保留命中
     *  entities/memory_key/memory_key_zh(高优) 的行(不卡阈值)，低优(memory_value/block_label)补到阈值。
     *  默认 10；0=禁用(不分组筛，仅受 HYBRID_MAX 截断)。 */
    public static final String RAG_MEMORY_KEYWORD_PER_BLOCK_THRESHOLD = "rag.memory.keyword-per-block-threshold";
    /** V38 LLM_KEY 粗筛 top-N（向量+BM25 RRF 融合后保留的候选记忆数）。默认 40；<1 → 40。 */
    public static final String RAG_MEMORY_LLM_KEY_COARSE_TOP_N = "rag.memory.llm-key.coarse-top-n";
    /** V38 LLM_KEY 精排开关（true=粗筛后灌 LLM filterRelevantKeys 双维度筛；false=直接注 top-N 不精排）。默认 true。 */
    public static final String RAG_MEMORY_LLM_KEY_RERANK = "rag.memory.llm-key.rerank";
    /** 关键词召回分词上限（替 MemoryService 硬编码 KEYWORD_MAX）：避免 SQL OR 列表过长。默认 8；0=不限。 */
    public static final String RAG_MEMORY_KEYWORD_MAX = "rag.memory.keyword-max";
    /** RAG 召回 query 多路扩展开关（true=改写+HyDE/切块多路；false=单 query 直接 embed）。
     *  4 条检索路径（/retrieve、/ask、Chat 注入、Agent/工作流）全读此键 → 调试与真实一致。默认 true。 */
    public static final String RAG_RECALL_EXPANSION_ENABLED = "rag.recall.expansion.enabled";
    /** RAG 召回扩展切块触发阈值（字数）。输入 > 阈值 → 切块多路召回（多主题不丢内容）；≤ 阈值 → 改写+HyDE。
     *  仅 expansion.enabled=true 时生效。默认 200。 */
    public static final String RAG_RECALL_EXPANSION_THRESHOLD = "rag.recall.expansion.threshold";

    private final SystemSettingMapper mapper;

    @Value("${jwt.access-expiration:900000}")
    private Long defaultAccessExpirationMs;

    public long getAccessTokenExpirationMs() {
        String value = getValue(ACCESS_TOKEN_EXPIRATION_MS);
        if (value == null || value.isBlank()) {
            return defaultAccessExpirationMs;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return defaultAccessExpirationMs;
        }
    }

    public AuthSettingsVO getAuthSettings() {
        return AuthSettingsVO.builder()
                .accessTokenExpirationMs(getAccessTokenExpirationMs())
                .build();
    }

    public AuthSettingsVO updateAuthSettings(long accessTokenExpirationMs) {
        upsert(ACCESS_TOKEN_EXPIRATION_MS, String.valueOf(accessTokenExpirationMs), "Access Token有效期(毫秒)");
        return getAuthSettings();
    }

    // ============================ 通用 boolean get/set ============================

    /** 通用读 boolean（值存 TEXT 'true'/'false'）；缺失/非法 → def。 */
    public boolean getBoolean(String key, boolean def) {
        String value = getValue(key);
        if (value == null || value.isBlank()) {
            return def;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /** 通用写 boolean。 */
    public void setBoolean(String key, boolean val, String description) {
        upsert(key, String.valueOf(val), description);
    }

    // ============================ RAG/记忆模式 ============================

    /** RAG/记忆模式全局总开关，默认 false（opt-in）。 */
    public boolean getRagMemoryEnabled() {
        return getBoolean(RAG_MEMORY_ENABLED, false);
    }

    public void updateRagMemoryEnabled(boolean enabled) {
        setBoolean(RAG_MEMORY_ENABLED, enabled, "RAG/记忆模式总开关（false=opt-in）");
    }

    /** 记忆处理模式，默认 ASYNC（全异步不卡顿）。HYBRID=同步即时冲突追问。 */
    public String getMemoryProcessMode() {
        return "HYBRID".equals(getValue(RAG_MEMORY_PROCESS_MODE)) ? "HYBRID" : "ASYNC";
    }

    public void updateMemoryProcessMode(String mode) {
        if (!"ASYNC".equals(mode) && !"HYBRID".equals(mode)) mode = "ASYNC";
        upsert(RAG_MEMORY_PROCESS_MODE, mode, "记忆处理模式（ASYNC=全异步不卡顿/HYBRID=同步即时冲突追问）");
    }

    /** 记忆检索模式，默认 LLM_FULL_CONTEXT（全量灌入）。
     *  EMBEDDING_VECTOR=向量 top-K 真检索；VECTOR_KEYWORD=向量+关键词(实体)hybrid+LLM-key兜底；
     *  LLM_KEY=anchor 向量+BM25 RRF 粗筛→LLM 双维度精排（百万 key 语义召回，V38）。 */
    public String getMemoryRetrievalMode() {
        String v = getValue(RAG_MEMORY_RETRIEVAL_MODE);
        if ("EMBEDDING_VECTOR".equals(v)) return "EMBEDDING_VECTOR";
        if ("VECTOR_KEYWORD".equals(v)) return "VECTOR_KEYWORD";
        if ("LLM_KEY".equals(v)) return "LLM_KEY";
        return "LLM_FULL_CONTEXT";
    }

    public void updateMemoryRetrievalMode(String mode) {
        if (!"LLM_FULL_CONTEXT".equals(mode) && !"EMBEDDING_VECTOR".equals(mode)
                && !"VECTOR_KEYWORD".equals(mode) && !"LLM_KEY".equals(mode)) {
            mode = "LLM_FULL_CONTEXT";
        }
        upsert(RAG_MEMORY_RETRIEVAL_MODE, mode,
                "记忆检索模式（LLM_FULL_CONTEXT=全量/EMBEDDING_VECTOR=向量top-K/VECTOR_KEYWORD=向量+关键词hybrid+LLM兜底/LLM_KEY=anchor语义两阶段）");
    }

    /** 记忆标签语言，默认 EN（英文 key，向后兼容）。ZH=中文 key_zh（空回退英文）。BOTH=中文(英文)双显。
     *  控制注入上下文里 key 的展示语言。 */
    public String getMemoryKeyLanguage() {
        String v = getValue(RAG_MEMORY_KEY_LANGUAGE);
        if ("ZH".equals(v)) return "ZH";
        if ("BOTH".equals(v)) return "BOTH";
        return "EN";
    }

    public void updateMemoryKeyLanguage(String lang) {
        if (!"ZH".equals(lang) && !"EN".equals(lang) && !"BOTH".equals(lang)) lang = "EN";
        upsert(RAG_MEMORY_KEY_LANGUAGE, lang, "记忆标签语言（EN=英文key默认/ZH=中文key_zh空回退英文/BOTH=中文(英文)双显）");
    }

    /** 全量模式记忆阈值，默认 20。记忆条数 > 阈值时改两阶段（先加载 key→LLM 选相关→再装 value）；
     *  返回 0=禁用（始终全量）。仅 LLM_FULL_CONTEXT 模式生效。非法/缺失 → 20。 */
    public int getMemoryFullContextThreshold() {
        String v = getValue(RAG_MEMORY_FULL_CONTEXT_THRESHOLD);
        if (v == null || v.isBlank()) return 20;
        try {
            int n = Integer.parseInt(v.trim());
            return n < 0 ? 20 : n;
        } catch (NumberFormatException ignored) {
            return 20;
        }
    }

    public void updateMemoryFullContextThreshold(int threshold) {
        if (threshold < 0) threshold = 0;
        upsert(RAG_MEMORY_FULL_CONTEXT_THRESHOLD, String.valueOf(threshold),
                "全量模式记忆阈值（>此值改两阶段LLM筛key；0=禁用始终全量，默认20）");
    }

    /** VECTOR_KEYWORD 关键词召回 per-block_label 阈值，默认 10。
     *  同一信息块命中 > 阈值时优先保留命中 entities/memory_key/memory_key_zh(高优)的行(不卡阈值)，
     *  低优(memory_value/block_label)补到阈值；组内 ≤ 阈值全留。返回 0=禁用(不分组筛)。非法/缺失 → 10。 */
    public int getMemoryKeywordPerBlockThreshold() {
        String v = getValue(RAG_MEMORY_KEYWORD_PER_BLOCK_THRESHOLD);
        if (v == null || v.isBlank()) return 10;
        try {
            int n = Integer.parseInt(v.trim());
            return n < 0 ? 10 : n;
        } catch (NumberFormatException ignored) {
            return 10;
        }
    }

    public void updateMemoryKeywordPerBlockThreshold(int threshold) {
        if (threshold < 0) threshold = 0;
        upsert(RAG_MEMORY_KEYWORD_PER_BLOCK_THRESHOLD, String.valueOf(threshold),
                "关键词召回per-block阈值（同block命中>此值优先留高优entities/key/key_zh；0=禁用，默认10）");
    }

    // ============================ V38 LLM_KEY 旋钮 + keyword-max ============================

    /** LLM_KEY 粗筛 top-N（向量+BM25 RRF 融合后保留候选记忆数），默认 40。<1 或非法 → 40。 */
    public int getLlmKeyCoarseTopN() {
        String v = getValue(RAG_MEMORY_LLM_KEY_COARSE_TOP_N);
        if (v == null || v.isBlank()) return 40;
        try {
            int n = Integer.parseInt(v.trim());
            return n < 1 ? 40 : n;
        } catch (NumberFormatException ignored) {
            return 40;
        }
    }

    public void updateLlmKeyCoarseTopN(int topN) {
        if (topN < 1) topN = 40;
        upsert(RAG_MEMORY_LLM_KEY_COARSE_TOP_N, String.valueOf(topN),
                "LLM_KEY粗筛top-N（向量+BM25 RRF融合后保留候选记忆数；<1=默认40）");
    }

    /** LLM_KEY 精排开关，默认 true（粗筛后灌 LLM 双维度筛；false=直接注 top-N 不精排）。 */
    public boolean getLlmKeyRerank() {
        return getBoolean(RAG_MEMORY_LLM_KEY_RERANK, true);
    }

    public void updateLlmKeyRerank(boolean rerank) {
        setBoolean(RAG_MEMORY_LLM_KEY_RERANK, rerank,
                "LLM_KEY精排开关（true=粗筛后LLM双维度筛/false=直接注top-N；默认true）");
    }

    /** 关键词召回分词上限（替 MemoryService 硬编码 KEYWORD_MAX），默认 8。0=不限；非法/缺失 → 8。 */
    public int getKeywordMax() {
        String v = getValue(RAG_MEMORY_KEYWORD_MAX);
        if (v == null || v.isBlank()) return 8;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ignored) {
            return 8;
        }
    }

    public void updateKeywordMax(int max) {
        if (max < 0) max = 0;
        upsert(RAG_MEMORY_KEYWORD_MAX, String.valueOf(max),
                "关键词召回分词上限（0=不限，避免SQL OR列表过长；默认8）");
    }

    // ============================ RAG 召回 query 扩展 ============================

    /** RAG 召回 query 多路扩展开关，默认 true（改写+HyDE/切块；关→单 query 直接 embed）。
     *  4 条检索路径同读此键，保证调试与真实一致。 */
    public boolean getRagRecallExpansionEnabled() {
        return getBoolean(RAG_RECALL_EXPANSION_ENABLED, true);
    }

    public void updateRagRecallExpansionEnabled(boolean enabled) {
        setBoolean(RAG_RECALL_EXPANSION_ENABLED, enabled,
                "RAG召回query扩展开关（true=改写+HyDE/切块多路；false=单query；4路同读，默认true）");
    }

    /** 扩展切块触发阈值（字数），默认 200。输入 > 阈值 → 切块多路召回；≤ 阈值 → 改写+HyDE。
     *  仅 expansion.enabled=true 生效。非法/缺失 → 200。 */
    public int getRagRecallExpansionThreshold() {
        String v = getValue(RAG_RECALL_EXPANSION_THRESHOLD);
        if (v == null || v.isBlank()) return 200;
        try {
            int n = Integer.parseInt(v.trim());
            return n < 1 ? 200 : n;
        } catch (NumberFormatException ignored) {
            return 200;
        }
    }

    public void updateRagRecallExpansionThreshold(int threshold) {
        if (threshold < 1) threshold = 200;
        upsert(RAG_RECALL_EXPANSION_THRESHOLD, String.valueOf(threshold),
                "扩展切块触发阈值字数（输入>此值切块多路召回；≤此值改写+HyDE；默认200）");
    }

    private String getValue(String key) {
        SystemSetting setting = mapper.selectOne(new LambdaQueryWrapper<SystemSetting>()
                .eq(SystemSetting::getSettingKey, key));
        return setting != null ? setting.getSettingValue() : null;
    }

    private void upsert(String key, String value, String description) {
        SystemSetting setting = mapper.selectOne(new LambdaQueryWrapper<SystemSetting>()
                .eq(SystemSetting::getSettingKey, key));
        OffsetDateTime now = OffsetDateTime.now();
        if (setting == null) {
            setting = new SystemSetting();
            setting.setSettingKey(key);
            setting.setSettingValue(value);
            setting.setDescription(description);
            setting.setCreatedAt(now);
            setting.setUpdatedAt(now);
            mapper.insert(setting);
        } else {
            setting.setSettingValue(value);
            setting.setDescription(description);
            setting.setUpdatedAt(now);
            mapper.updateById(setting);
        }
    }
}
