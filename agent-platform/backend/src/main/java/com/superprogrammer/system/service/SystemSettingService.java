package com.superprogrammer.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.llm.service.AesEncryptService;
import com.superprogrammer.system.dto.AuthSettingsVO;
import com.superprogrammer.system.dto.MemoryEntitiesConfig;
import com.superprogrammer.system.entity.SystemSetting;
import com.superprogrammer.system.mapper.SystemSettingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSettingService {
    public static final String ACCESS_TOKEN_EXPIRATION_MS = "auth.access_token_expiration_ms";
    /** 安全体系 S2 · A8（SEC-FR-008）：单点登录开关（默认开；关=恢复多会话，秒级回滚）。 */
    public static final String AUTH_SINGLE_SESSION_ENABLED = "auth.single_session.enabled";
    /** 安全体系 S2 · L7（SEC-FR-126）：低余额并行闸门阈值（积分，默认 100；0=关闭闸门）。 */
    public static final String BILLING_LOW_BALANCE_THRESHOLD = "billing.low-balance.threshold";
    /** 安全体系 S2 · L7（SEC-FR-126）：低余额时允许的最大在途任务数（默认 1）。 */
    public static final String BILLING_LOW_BALANCE_MAX_INFLIGHT = "billing.low-balance.max-inflight";
    /** RAG/记忆模式总开关（false=opt-in）。4 层优先级：session>agent/workflow>global。 */
    public static final String RAG_MEMORY_ENABLED = "rag.memory.enabled";
    /** 计划12 · C：非项目会话个人记忆 gen 兜底开关（项目会话走 owner AND 会员覆写独立表）。默认 true。 */
    public static final String RAG_MEMORY_GEN_PERSONAL_ENABLED = "rag.memory.gen.personal.enabled";
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
    /** M3 entities 词袋计数配置（JSON：totalMax/variantMin-Max/properNounMin-Max/hypernymMin-Max）。
     *  控制 MemoryConflictJudge 抽取 prompt 数量指引 + Java readEntities 兜底截断。默认值 = V38 硬上限。 */
    public static final String RAG_MEMORY_ENTITIES_CONFIG = "rag.memory.entities-config";
    /** RAG 召回 query 多路扩展开关（true=改写+HyDE/切块多路；false=单 query 直接 embed）。
     *  4 条检索路径（/retrieve、/ask、Chat 注入、Agent/工作流）全读此键 → 调试与真实一致。默认 true。 */
    public static final String RAG_RECALL_EXPANSION_ENABLED = "rag.recall.expansion.enabled";
    /** RAG 召回扩展切块触发阈值（字数）。输入 > 阈值 → 切块多路召回（多主题不丢内容）；≤ 阈值 → 改写+HyDE。
     *  仅 expansion.enabled=true 时生效。默认 200。 */
    public static final String RAG_RECALL_EXPANSION_THRESHOLD = "rag.recall.expansion.threshold";
    /** Excel 多Sheet导入解析阈值（设计 §4.6）。列数 > 此值 → 宽表行流兜底。默认 10。 */
    public static final String KNOWLEDGE_EXCEL_COL_THRESHOLD = "knowledge.excel.col-threshold";
    /** 每 Section 最大行数（防超大 section）。默认 200。 */
    public static final String KNOWLEDGE_EXCEL_ROW_CHUNK_SIZE = "knowledge.excel.row-chunk-size";
    /** 单 cell 文本截断长度。默认 200。 */
    public static final String KNOWLEDGE_EXCEL_CELL_MAX_CHARS = "knowledge.excel.cell-max-chars";
    /** 单 sheet 行数硬上限（截断防 OOM）。默认 5000。 */
    public static final String KNOWLEDGE_EXCEL_MAX_ROWS_PER_SHEET = "knowledge.excel.max-rows-per-sheet";
    /** 预读端点返回 sheet 名上限（防恶意巨多 sheet 文件）。默认 50。 */
    public static final String KNOWLEDGE_EXCEL_PREVIEW_MAX_SHEETS = "knowledge.excel.preview-max-sheets";

    // ============================ 计划12 个人记忆 memory.* ============================
    /** 标签归一 anchor 余弦距离阈值（写时归一路径③粗筛门槛）。
     *  &lt;= 阈值的候选才送二次 LLM 批判判同义。默认 0.25（距离语义：0=完全相同，2=相反）。
     *  免发版可调：调小=更保守少并、调大=更激进易并（误并不可逆，倾向保守）。 */
    public static final String MEMORY_TAG_ANCHOR_THRESHOLD = "memory.tag.anchor-threshold";
    /** 前置过滤用户加项敏感黑名单（逗号分隔；用户只能加项，核心项由 Prefilter 内置不可清空）。
     *  命中即跳该侧生成（不写 raw 也不调 LLM）。默认空（核心项已预置开箱即用）。 */
    public static final String MEMORY_PREFILTER_BLACKLIST_EXTRA = "memory.prefilter.blacklist-extra";

    // ============================ 记忆二期 P1 路由 memory.routing.* ============================
    /** 路由总开关：false=全停自动收录（不影响个人流水账写入）。默认 true。 */
    public static final String MEMORY_ROUTING_ENABLED = "memory.routing.enabled";
    /** 路由粗筛 anchor 余弦距离阈值（0=完全相同，2=相反）。低于粗筛阈值 → 零 LLM 调用。默认 0.35（严于标签归一 0.25）。 */
    public static final String MEMORY_ROUTING_COARSE_THRESHOLD = "memory.routing.coarse-threshold";
    /** 路由置信度 ≥ 此值条目直接 ACTIVE（D1 定案默认 0.8）。 */
    public static final String MEMORY_ROUTING_AUTO_APPROVE_THRESHOLD = "memory.routing.auto-approve-threshold";
    /** 路由置信度 ≥ 此值且 < auto-approve 进 PENDING_REVIEW；低于此丢弃（D1 定案默认 0.5）。 */
    public static final String MEMORY_ROUTING_REVIEW_THRESHOLD = "memory.routing.review-threshold";
    /** 记忆管线 LLM 默认 model（路由/蒸馏/生成/压缩/冲突/召回标签）。请求域被对话所选 model 覆盖，
     *  后台域读源 chat_model，NULL/混合回退本值。默认 doubao-seed-2.0-code。 */
    public static final String MEMORY_JUDGE_MODEL = "memory.judge.model";

    // ============================ 联网搜索 search.* ============================
    /** 联网搜索全局总开关（false=禁用，开关前端也读不到结果）。默认 false。 */
    public static final String SEARCH_ENABLED = "search.enabled";
    /** 当前生效 provider：tavily/serper/bing/builtin。非法值 → builtin（兜底）。 */
    public static final String SEARCH_ACTIVE_PROVIDER = "search.active-provider";
    /** 默认返回结果数 top-N。默认 5。 */
    public static final String SEARCH_MAX_RESULTS = "search.max-results";
    /** 单次搜索整体超时 ms。默认 10000。 */
    public static final String SEARCH_TIMEOUT_MS = "search.timeout-ms";
    /** 外部供应商 API key（AES 加密存，不回显明文，仿 LLM provider key 范式）。 */
    public static final String SEARCH_TAVILY_KEY = "search.tavily.api-key";
    public static final String SEARCH_SERPER_KEY = "search.serper.api-key";
    public static final String SEARCH_BING_KEY = "search.bing.api-key";
    /** provider 名白名单（写 active_provider 时校验，防注入非法值）。 */
    public static final Set<String> SEARCH_PROVIDER_WHITELIST = Set.of("tavily", "serper", "bing", "builtin");

    private final SystemSettingMapper mapper;
    private final AesEncryptService aesEncryptService;
    private final ObjectMapper objectMapper;

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
                .singleSessionEnabled(getBoolean(AUTH_SINGLE_SESSION_ENABLED, true))
                .build();
    }

    public AuthSettingsVO updateAuthSettings(long accessTokenExpirationMs, Boolean singleSessionEnabled) {
        upsert(ACCESS_TOKEN_EXPIRATION_MS, String.valueOf(accessTokenExpirationMs), "Access Token有效期(毫秒)");
        if (singleSessionEnabled != null) {
            upsert(AUTH_SINGLE_SESSION_ENABLED, String.valueOf(singleSessionEnabled),
                    "A8 单点登录开关（SEC-FR-008）：同账号仅一处在线，新登录踢旧会话");
        }
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

    // ============================ 通用 long get/set ============================

    /** 通用读 long；缺失/非法 → def（实时查库，管理员页面改即生效）。 */
    public long getLong(String key, long def) {
        String value = getValue(key);
        if (value == null || value.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    // ============================ 计费（安全体系 S2 · L7 低余额并行闸门，SEC-FR-126）============================

    public long getLowBalanceThreshold() {
        return getLong(BILLING_LOW_BALANCE_THRESHOLD, 100);
    }

    public long getLowBalanceMaxInflight() {
        return getLong(BILLING_LOW_BALANCE_MAX_INFLIGHT, 1);
    }

    public com.superprogrammer.system.dto.BillingSettingsVO getBillingSettings() {
        return com.superprogrammer.system.dto.BillingSettingsVO.builder()
                .lowBalanceThreshold(getLowBalanceThreshold())
                .lowBalanceMaxInflight(getLowBalanceMaxInflight())
                .build();
    }

    public com.superprogrammer.system.dto.BillingSettingsVO updateBillingSettings(
            Long lowBalanceThreshold, Long lowBalanceMaxInflight) {
        if (lowBalanceThreshold != null) {
            upsert(BILLING_LOW_BALANCE_THRESHOLD, String.valueOf(lowBalanceThreshold),
                    "L7 低余额并行闸门阈值（SEC-FR-126）：余额低于此值禁多任务并行");
        }
        if (lowBalanceMaxInflight != null) {
            upsert(BILLING_LOW_BALANCE_MAX_INFLIGHT, String.valueOf(lowBalanceMaxInflight),
                    "L7 低余额最大在途任务数（SEC-FR-126），默认 1");
        }
        return getBillingSettings();
    }

    // ============================ RAG/记忆模式 ============================

    /** RAG/记忆模式全局总开关，默认 false（opt-in）。 */
    public boolean getRagMemoryEnabled() {
        return getBoolean(RAG_MEMORY_ENABLED, false);
    }

    public void updateRagMemoryEnabled(boolean enabled) {
        setBoolean(RAG_MEMORY_ENABLED, enabled, "RAG/记忆模式总开关（false=opt-in）");
    }

    /** 计划12 · C：非项目会话个人记忆 gen 兜底开关，默认 true（开生成）。
     *  项目会话走 memory_project_settings（owner）AND memory_project_user_settings（会员覆写）表，
     *  非项目会话（无 projectId）读此全局兜底。 */
    public boolean getMemoryGenPersonalEnabled() {
        return getBoolean(RAG_MEMORY_GEN_PERSONAL_ENABLED, true);
    }

    public void updateMemoryGenPersonalEnabled(boolean enabled) {
        setBoolean(RAG_MEMORY_GEN_PERSONAL_ENABLED, enabled,
                "非项目会话个人记忆gen兜底开关（项目会话走ownerAND会员覆写表；默认true开生成）");
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

    // ============================ M3 entities 词袋计数配置 ============================

    /** entities 词袋计数配置，默认 = V38 硬上限（零行为变更）。JSON 损坏/缺失 → 默认 + 告警 log。 */
    public MemoryEntitiesConfig getMemoryEntitiesConfig() {
        String json = getValue(RAG_MEMORY_ENTITIES_CONFIG);
        if (json == null || json.isBlank()) {
            return MemoryEntitiesConfig.defaults();
        }
        try {
            MemoryEntitiesConfig parsed = objectMapper.readValue(json, MemoryEntitiesConfig.class);
            return parsed.normalized();
        } catch (Exception e) {
            log.warn("memoryEntitiesConfig 读取失败，回退默认值（json={}）traceId见请求上下文：{}", json, e.getMessage());
            return MemoryEntitiesConfig.defaults();
        }
    }

    /** 写 entities 词袋计数配置（已 normalized，存 JSON 串）。 */
    public MemoryEntitiesConfig updateMemoryEntitiesConfig(MemoryEntitiesConfig config) {
        MemoryEntitiesConfig normalized = config.normalized();
        try {
            String json = objectMapper.writeValueAsString(normalized);
            upsert(RAG_MEMORY_ENTITIES_CONFIG, json,
                    "entities词袋计数配置（totalMax/variantMin-Max/properNounMin-Max/hypernymMin-Max，默认V38硬上限）");
        } catch (Exception e) {
            log.error("memoryEntitiesConfig 写入失败：{}", e.getMessage(), e);
        }
        return normalized;
    }

    // ============================ Excel 多Sheet导入解析阈值（设计 §4.6） ============================

    private int getExcelInt(String key, int def, int min) {
        String v = getValue(key);
        if (v == null || v.isBlank()) return def;
        try {
            int n = Integer.parseInt(v.trim());
            return n < min ? def : n;
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    /** 列数 > 此值 → 宽表行流兜底。默认 10。 */
    public int getExcelColThreshold() {
        return getExcelInt(KNOWLEDGE_EXCEL_COL_THRESHOLD, 10, 1);
    }

    /** 每 Section 最大行数（防超大 section）。默认 200。 */
    public int getExcelRowChunkSize() {
        return getExcelInt(KNOWLEDGE_EXCEL_ROW_CHUNK_SIZE, 200, 1);
    }

    /** 单 cell 文本截断长度。默认 200。 */
    public int getExcelCellMaxChars() {
        return getExcelInt(KNOWLEDGE_EXCEL_CELL_MAX_CHARS, 200, 1);
    }

    /** 单 sheet 行数硬上限（截断防 OOM）。默认 5000。 */
    public int getExcelMaxRowsPerSheet() {
        return getExcelInt(KNOWLEDGE_EXCEL_MAX_ROWS_PER_SHEET, 5000, 1);
    }

    /** 预读端点返回 sheet 名上限（防恶意巨多 sheet 文件）。默认 50。 */
    public int getExcelPreviewMaxSheets() {
        return getExcelInt(KNOWLEDGE_EXCEL_PREVIEW_MAX_SHEETS, 50, 1);
    }

    // ============================ 计划12 标签归一 ============================

    /** 标签归一 anchor 余弦距离阈值（路径③粗筛门槛），默认 0.25。距离语义：0=完全相同，2=相反。
     *  &lt;= 阈值的候选才送二次 LLM 批判。非法/缺失 → 0.25；越界 [0,2] → 0.25。 */
    public double getMemoryTagAnchorThreshold() {
        String v = getValue(MEMORY_TAG_ANCHOR_THRESHOLD);
        if (v == null || v.isBlank()) return 0.25;
        try {
            double d = Double.parseDouble(v.trim());
            return (d < 0.0 || d > 2.0) ? 0.25 : d;
        } catch (NumberFormatException ignored) {
            return 0.25;
        }
    }

    /** 写标签归一 anchor 阈值（免发版调）。 */
    public void updateMemoryTagAnchorThreshold(double threshold) {
        if (threshold < 0.0 || threshold > 2.0) threshold = 0.25;
        upsert(MEMORY_TAG_ANCHOR_THRESHOLD, String.valueOf(threshold),
                "标签归一anchor余弦距离阈值（路径③粗筛门槛，0=完全相同2=相反，默认0.25保守）");
    }

    // ============================ 记忆二期 P1 路由 ============================

    /** 路由总开关（false=全停自动收录，不影响个人流水账）。默认 true。 */
    public boolean getMemoryRoutingEnabled() {
        return getBoolean(MEMORY_ROUTING_ENABLED, true);
    }

    /** 路由粗筛 anchor 余弦距离阈值（距离语义：0=同，2=反），默认 0.35。非法/越界 → 0.35。 */
    public double getMemoryRoutingCoarseThreshold() {
        return getDoubleInRange(MEMORY_ROUTING_COARSE_THRESHOLD, 0.35, 0.0, 2.0);
    }

    /** 路由置信度直接收录阈值（0~1），默认 0.8。 */
    public double getMemoryRoutingAutoApproveThreshold() {
        return getDoubleInRange(MEMORY_ROUTING_AUTO_APPROVE_THRESHOLD, 0.8, 0.0, 1.0);
    }

    /** 路由置信度待审核阈值（0~1），默认 0.5。 */
    public double getMemoryRoutingReviewThreshold() {
        return getDoubleInRange(MEMORY_ROUTING_REVIEW_THRESHOLD, 0.5, 0.0, 1.0);
    }

    /** 记忆管线 LLM 默认 model：读 memory.judge.model，空/缺失回退 RagConfig.MEMORY_JUDGE_MODEL 常量。
     *  请求域被对话所选 model 覆盖；后台域读源 chat_model，NULL/混合回退本值。 */
    public String getMemoryJudgeModel() {
        String v = getValue(MEMORY_JUDGE_MODEL);
        return (v == null || v.isBlank()) ? com.superprogrammer.knowledge.service.RagConfig.MEMORY_JUDGE_MODEL : v;
    }

    /** 通用读 double（范围校验，非法/越界 → def）。 */
    private double getDoubleInRange(String key, double def, double min, double max) {
        String v = getValue(key);
        if (v == null || v.isBlank()) return def;
        try {
            double d = Double.parseDouble(v.trim());
            return (d < min || d > max) ? def : d;
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    /** 前置过滤用户加项敏感黑名单（逗号分隔解析为 List，trim + 去空）。
     *  <b>核心项不可清空</b>：核心 6 类（密码/支付/验证码/身份证/银行卡/token）由 Prefilter 内置，
     *  本配置仅能在其之上加项。默认空 List。 */
    public java.util.List<String> getMemoryPrefilterBlacklistExtra() {
        String v = getValue(MEMORY_PREFILTER_BLACKLIST_EXTRA);
        if (v == null || v.isBlank()) return java.util.Collections.emptyList();
        return java.util.Arrays.stream(v.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 写用户加项敏感黑名单（逗号拼接；核心项仍由 Prefilter 内置不受此影响）。 */
    public void updateMemoryPrefilterBlacklistExtra(java.util.List<String> terms) {
        java.util.List<String> clean = terms == null ? java.util.Collections.emptyList()
                : terms.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
        upsert(MEMORY_PREFILTER_BLACKLIST_EXTRA, String.join(",", clean),
                "前置过滤用户加项敏感黑名单（逗号分隔；核心项不可清空，仅在其上加项）");
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

    // ============================ 联网搜索 search.* ============================

    /** 联网搜索总开关，默认 false（opt-in）。 */
    public boolean getSearchEnabled() {
        return getBoolean(SEARCH_ENABLED, false);
    }

    public void updateSearchEnabled(boolean enabled) {
        setBoolean(SEARCH_ENABLED, enabled, "联网搜索总开关（false=禁用）");
    }

    /** 当前生效 provider，默认 builtin（无外部 key 兜底）。非法值 → builtin。 */
    public String getActiveSearchProvider() {
        String v = getValue(SEARCH_ACTIVE_PROVIDER);
        return SEARCH_PROVIDER_WHITELIST.contains(v) ? v : "builtin";
    }

    public void updateActiveSearchProvider(String provider) {
        if (!SEARCH_PROVIDER_WHITELIST.contains(provider)) {
            provider = "builtin";
        }
        upsert(SEARCH_ACTIVE_PROVIDER, provider, "当前联网搜索 provider（tavily/serper/bing/builtin）");
    }

    /** 默认 top-N，默认 5。非法/缺失 → 5。 */
    public int getSearchMaxResults() {
        String v = getValue(SEARCH_MAX_RESULTS);
        if (v == null || v.isBlank()) return 5;
        try {
            int n = Integer.parseInt(v.trim());
            return n < 1 || n > 10 ? 5 : n;
        } catch (NumberFormatException ignored) {
            return 5;
        }
    }

    public void updateSearchMaxResults(int max) {
        if (max < 1 || max > 10) max = 5;
        upsert(SEARCH_MAX_RESULTS, String.valueOf(max), "联网搜索默认 top-N（1~10，默认5）");
    }

    /** 单次搜索整体超时 ms，默认 10000。非法/缺失 → 10000。 */
    public int getSearchTimeoutMs() {
        String v = getValue(SEARCH_TIMEOUT_MS);
        if (v == null || v.isBlank()) return 10000;
        try {
            int n = Integer.parseInt(v.trim());
            return n < 1000 ? 10000 : n;
        } catch (NumberFormatException ignored) {
            return 10000;
        }
    }

    public void updateSearchTimeoutMs(int ms) {
        if (ms < 1000) ms = 10000;
        upsert(SEARCH_TIMEOUT_MS, String.valueOf(ms), "联网搜索单次整体超时ms（默认10000）");
    }

    // ---------- AES 加密 key 读写（通用，仿 LlmProviderService.getDecryptedApiKey） ----------

    /** 读加密 key 并解密。无值/解密失败 → 返回 null（provider available() 判 false 走降级）。 */
    public String getDecryptedValue(String key) {
        String cipher = getValue(key);
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        try {
            return aesEncryptService.decrypt(cipher);
        } catch (Exception e) {
            return null;
        }
    }

    /** 加密写 key（明文 → AES → upsert）。 */
    public void upsertEncrypted(String key, String plaintext, String description) {
        upsert(key, aesEncryptService.encrypt(plaintext), description);
    }

    /** 取指定 provider 的 API key 明文（tavily/serper/bing）。 */
    public String getSearchApiKey(String provider) {
        return switch (provider) {
            case "tavily" -> getDecryptedValue(SEARCH_TAVILY_KEY);
            case "serper" -> getDecryptedValue(SEARCH_SERPER_KEY);
            case "bing" -> getDecryptedValue(SEARCH_BING_KEY);
            default -> null;
        };
    }

    /** 加密写 provider key（明文 → AES → upsert；后台配置页用，不回显明文）。 */
    public void upsertSearchApiKey(String provider, String plaintext) {
        switch (provider) {
            case "tavily" -> upsertEncrypted(SEARCH_TAVILY_KEY, plaintext, "Tavily API key（AES 加密）");
            case "serper" -> upsertEncrypted(SEARCH_SERPER_KEY, plaintext, "Serper API key（AES 加密）");
            case "bing" -> upsertEncrypted(SEARCH_BING_KEY, plaintext, "Bing API key（AES 加密）");
            default -> { /* 白名单外忽略 */ }
        }
    }

    /** 清除 provider key（空串写入后台 = 清除；置 null 让 selectOne 返 null）。 */
    public void clearSearchApiKey(String provider) {
        switch (provider) {
            case "tavily" -> removeKey(SEARCH_TAVILY_KEY);
            case "serper" -> removeKey(SEARCH_SERPER_KEY);
            case "bing" -> removeKey(SEARCH_BING_KEY);
            default -> { /* 白名单外忽略 */ }
        }
    }

    /** 物理删 key 行（AES 加密 key 清除用；与逻辑删体系独立，system_settings 无 deleted 列）。 */
    private void removeKey(String key) {
        SystemSetting setting = mapper.selectOne(new LambdaQueryWrapper<SystemSetting>()
                .eq(SystemSetting::getSettingKey, key));
        if (setting != null) {
            mapper.deleteById(setting.getId());
        }
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
