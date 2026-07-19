import request from './request'
import type { ApiResponse } from './request'

export interface AuthSettings {
  accessTokenExpirationMs: number
}

export interface RagMemorySettings {
  enabled: boolean
  /** 记忆处理模式：ASYNC=全异步(答完即结束不卡,冲突走面板) / HYBRID=同步(即时冲突追问,答完略卡) */
  processMode?: 'ASYNC' | 'HYBRID'
  /** 记忆检索模式：LLM_FULL_CONTEXT=全量灌入(默认) / EMBEDDING_VECTOR=向量top-K真检索(仅注入相关) / VECTOR_KEYWORD=向量+关键词(实体)hybrid+LLM兜底 / LLM_KEY=锚点语义两阶段(百万key,召回优先) */
  retrievalMode?: 'LLM_FULL_CONTEXT' | 'EMBEDDING_VECTOR' | 'VECTOR_KEYWORD' | 'LLM_KEY'
  /** 记忆标签语言：EN=英文 key(默认) / ZH=中文 key_zh(空回退英文) / BOTH=中英双显 key_zh(key)。控制注入上下文用哪个 key 展示。 */
  keyLanguage?: 'EN' | 'ZH' | 'BOTH'
  /** 全量模式记忆阈值（>此值改两阶段LLM筛key；0=禁用始终全量，默认20）。仅 LLM_FULL_CONTEXT 生效。 */
  fullContextThreshold?: number
  /** 关键词召回 per-block_label 阈值（同block命中>此值优先留高优entities/key/key_zh；0=禁用，默认10）。仅 VECTOR_KEYWORD 生效。 */
  keywordPerBlockThreshold?: number
  /** LLM_KEY 粗筛 top-N（向量+BM25 RRF 融合后保留候选数，默认40）。仅 LLM_KEY 生效。 */
  llmKeyCoarseTopN?: number
  /** LLM_KEY 精排开关（true=粗筛top-N→LLM双维度筛key/block；false=跳精排直接注top-N，默认true）。仅 LLM_KEY 生效。 */
  llmKeyRerank?: boolean
  /** 关键词通道最大召回块数（0=不限，默认8）。替 MemoryService KEYWORD_MAX 硬编码。 */
  keywordMax?: number
}

/** RAG 召回 query 扩展全局设置（4 路同读：/retrieve、/ask、Chat、Agent/工作流 → 调试=真实） */
export interface RagRecallSettings {
  /** 扩展开关：true=改写+HyDE/切块多路；false=单 query 直接 embed。默认 true。 */
  enabled: boolean
  /** 切块触发阈值（字数）。输入>此值→切块多路召回；≤此值→改写+HyDE。默认 200。 */
  threshold?: number
}

export const systemApi = {
  getAuthSettings() {
    return request.get<ApiResponse<AuthSettings>>('/system/settings/auth')
  },

  updateAuthSettings(data: AuthSettings) {
    return request.put<ApiResponse<AuthSettings>>('/system/settings/auth', data)
  },

  // RAG/记忆模式全局开关（V26）
  getRagMemorySettings() {
    return request.get<ApiResponse<RagMemorySettings>>('/system/settings/rag-memory')
  },

  updateRagMemorySettings(data: RagMemorySettings) {
    return request.put<ApiResponse<RagMemorySettings>>('/system/settings/rag-memory', data)
  },

  // 老记忆实体标签回填（V31 迁移补丁，异步，幂等可重跑）
  backfillMemoryEntities() {
    return request.post<ApiResponse<string>>('/system/settings/rag-memory/backfill-entities')
  },

  // 老记忆关键词重抽（维护用，全量按当前 prompt 重抽 entities 词袋，保留 key_zh，异步）
  reextractMemoryEntities() {
    return request.post<ApiResponse<string>>('/system/settings/rag-memory/reextract-entities')
  },

  // 历史记忆冲突脏数据清理（旧 KEEP_BOTH 双行残留：conflict 已 RESOLVED 但行仍带 conflict_id，异步幂等）
  cleanupMemoryResidue() {
    return request.post<ApiResponse<string>>('/system/settings/rag-memory/cleanup-memory-residue')
  },

  // RAG 召回 query 扩展全局开关（4 路同读：/retrieve、/ask、Chat、Agent/工作流）
  getRagRecallSettings() {
    return request.get<ApiResponse<RagRecallSettings>>('/system/settings/rag-recall')
  },

  updateRagRecallSettings(data: RagRecallSettings) {
    return request.put<ApiResponse<RagRecallSettings>>('/system/settings/rag-recall', data)
  },

  // ---- 联网搜索运维配置 ----
  getWebSearchSettings() {
    return request.get<ApiResponse<WebSearchSettings>>('/system/settings/web-search')
  },

  updateWebSearchSettings(data: Partial<WebSearchSettingsUpdate>) {
    return request.put<ApiResponse<WebSearchSettings>>('/system/settings/web-search', data)
  },

  testWebSearch() {
    return request.post<ApiResponse<WebSearchTestResult>>('/system/settings/web-search/test')
  }
}

/** 联网搜索配置回显（key 不回显明文，仅 hasXxxKey 布尔）。 */
export interface WebSearchSettings {
  enabled: boolean
  activeProvider: string
  maxResults: number
  timeoutMs: number
  hasTavilyKey: boolean
  hasSerperKey: boolean
  hasBingKey: boolean
  builtinConfigured: boolean
  providerAvailability: Record<string, boolean>
}

/** 写入：所有字段可选（null/undefined=不改）；key 空串=清除。 */
export interface WebSearchSettingsUpdate {
  enabled?: boolean
  activeProvider?: string
  maxResults?: number
  timeoutMs?: number
  tavilyKey?: string
  serperKey?: string
  bingKey?: string
}

export interface WebSearchTestResult {
  results: number
  providerAvailability: Record<string, boolean>
  activeProvider: string
  enabled: boolean
}
