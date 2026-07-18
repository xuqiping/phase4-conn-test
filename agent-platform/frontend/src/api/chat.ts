import request from './request'
import type { ApiResponse } from './request'
import { getStorage, STORAGE_KEYS } from '@/utils/storage'

export interface ChatSession {
  id: number
  title: string | null
  agentId: number | null
  agentName: string | null
  workflowId: number | null
  workflowName: string | null
  mode: string
  status: string
  createdAt: string
  updatedAt: string
}

export interface ChatMessage {
  id: number
  sessionId: number
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string
  metadata: string | null
  createdAt: string
}

export interface ChatResponse {
  sessionId: number
  messageId: number
  content: string
  mode: string
  metadata: string | null
}

export interface ChatSendRequest {
  message: string
  agentId?: number
  workflowId?: number
  model?: string
  /** 记忆模式开关（V26，非 null 持久化到会话）。 */
  ragEnabled?: boolean
  /** 项目记忆写目标（V33，null=总记忆会话）。 */
  projectId?: number | null
  /** 读开关：是否注入总记忆（V33，非 null 时持久化 = scope 更新标记）。 */
  memIncludeGlobal?: boolean
  /** 读开关：开启读取的项目集合（V33）。 */
  memReadProjectIds?: number[]
}

/** 用户侧全局记忆开关只读视图（GET /chat/memories/rag-mode，非 admin）。 */
export interface ChatRagMode {
  /** 全局总开关；会话级开关 null=继承此值。 */
  globalEnabled: boolean
}

/** 用户长期记忆（对应后端 UserMemoryVO，自服务查询/管理）。 */
export interface UserMemory {
  id: number
  category: string | null         // PREFERENCE / FACT / FEEDBACK
  memoryKey: string | null
  memoryKeyZh: string | null       // 中文标签（如"女儿"），「名称」列显示
  memoryValue: string | null
  blockLabel: string | null        // 信息块标签（如"家庭/个人信息"），「信息块」列显示
  source: string | null           // INFERRED / EXPLICIT
  confidence: number | null       // 0-1，注入阈值 ≥0.5
  createdAt: string
  updatedAt: string
  conflictId: number | null
  conflictStatus: string | null   // null / FLAGGED
  conflictWith: string | null     // counterpart 摘要
  // 项目记忆 scope（V33）
  isGlobal: boolean | null        // true=总记忆，false=仅项目
  projectIds: number[] | null     // 挂载的项目 id
  homeProjectId: number | null    // 写归属 home（V34，null=总记忆 home）；M1 归属列区分归属/共享
}

/** 记忆行内编辑请求（M1，PUT /memories/{id}）。 */
export interface MemoryEditRequest {
  memoryKey?: string
  memoryKeyZh?: string
  memoryValue?: string
  blockLabel?: string
}

/** 冲突候选单条（MemoryCandidateVO）。 */
export interface MemoryCandidate {
  id: number | null               // 新事实未入库 → null
  memoryKey: string | null
  memoryValue: string | null
  category: string | null
}

/** 冲突分组（MemoryConflictVO，GET /memories/conflicts 返回）。 */
export interface MemoryConflict {
  conflictId: number
  block: string | null
  candidates: MemoryCandidate[]
  status: string                  // FLAGGED
  askText: string | null
  createdAt: string
}

/** 粗筛候选行（V38 LLM_KEY/VECTOR_KEYWORD 召回过程透出）。 */
export interface MemoryPreviewCandidate {
  memoryKeyZh: string | null      // key_zh（空→null，前端回退英文 key）
  memoryKey: string | null        // 英文 key（key_zh 空时兜底展示）
  valuePreview: string | null     // value 截断预览（~60 字）
  blockLabel: string | null       // 信息块标签
  scope: string | null            // global / project（is_global）
  channel: string | null          // 命中通道：vector / bm25 / both / keyword
}

/** 各召回通道命中统计（V38）。null=该模式无此通道；数值=命中条数。 */
export interface MemoryPreviewChannels {
  vector: number | null           // 向量（anchor/EMBEDDING_VECTOR）命中数
  keyword: number | null          // 关键词（VECTOR_KEYWORD entities 列）命中数
  bm25: number | null             // BM25（anchor_tokens_tsv）命中数
  llmFallback: boolean | null     // 是否触发 LLM-key 兜底（VECTOR_KEYWORD 0 命中救场）
}

/** 记忆注入预览（POST /memories/preview，调试用）：展示各检索设置的实际效果 + 注入 LLM 的上下文。 */
export interface MemoryContextPreview {
  mode: string                    // LLM_FULL_CONTEXT / EMBEDDING_VECTOR / VECTOR_KEYWORD / LLM_KEY
  keyLanguage: string             // EN / ZH / BOTH
  threshold: number               // 全量阈值
  totalMemories: number           // confidence≥0.5 记忆总数
  twoStage: boolean               // 是否走超阈值两阶段
  context: string | null          // 注入文本，null=不注入
  // V38 召回过程透出（LLM_KEY/VECTOR_KEYWORD/两阶段才有，否则 null）
  candidates: MemoryPreviewCandidate[] | null  // 粗筛 top-N 候选（按 RRF 分降序）
  selectedKeys: string[] | null   // LLM 精排选中 memory_key 列表（rerank / 超阈值两阶段）
  channels: MemoryPreviewChannels | null       // 各召回通道命中计数
}

/** 记忆处理状态（GET /memories/status，状态条 3s 轮询用）。 */
export interface MemoryStatus {
  processingCount: number         // 进行中的抽取任务数（>0 显「记忆记录中…」）
  conflictCount: number           // 待处理冲突数（动态 +1/-1，归零隐）
}

/** 记忆 scope 归属（GET/PUT /memories/{id}/scopes）。 */
export interface MemoryScopeVO {
  isGlobal: boolean | null
  projectIds: number[] | null
}

/** M2:per-key 时序事实标(GET/PUT /memories/key-meta/{key})。isTemporal=null=首次待询问。 */
export interface MemoryKeyMeta {
  memoryKey: string
  isTemporal: boolean | null
  source: string | null   // LLM_ASK / USER_OVERRIDE
}

export const chatApi = {
  createSession(data: ChatSendRequest) {
    return request.post<ApiResponse<ChatSession>>('/chat/sessions', data)
  },

  listSessions() {
    return request.get<ApiResponse<ChatSession[]>>('/chat/sessions')
  },

  getSession(id: number) {
    return request.get<ApiResponse<ChatSession>>(`/chat/sessions/${id}`)
  },

  deleteSession(id: number) {
    return request.delete<ApiResponse<void>>(`/chat/sessions/${id}`)
  },
  /** DELETE /api/chat/sessions/batch — 批量删会话（ownership 过滤），返实删条数 */
  batchDeleteSessions(ids: number[]) {
    return request.delete<ApiResponse<number>>('/chat/sessions/batch', { data: ids })
  },

  updateSessionTarget(id: number, data: Pick<ChatSendRequest, 'agentId' | 'workflowId'>) {
    return request.put<ApiResponse<ChatSession>>(`/chat/sessions/${id}/target`, data)
  },

  getMessages(sessionId: number) {
    return request.get<ApiResponse<ChatMessage[]>>(`/chat/sessions/${sessionId}/messages`)
  },

  sendMessage(sessionId: number, data: { message: string; model?: string; ragEnabled?: boolean }) {
    return request.post<ApiResponse<ChatResponse>>(`/chat/sessions/${sessionId}/messages`, data)
  },

  sendNewMessage(data: ChatSendRequest) {
    return request.post<ApiResponse<ChatResponse>>('/chat/messages', data)
  },

  // Streaming (SSE)
  streamMessage(sessionId: number, data: { message: string; model?: string; ragEnabled?: boolean }) {
    const token = getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN) || ''
    return fetch(`/api/chat/sessions/${sessionId}/messages/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(data)
    })
  },

  streamNewMessage(data: ChatSendRequest) {
    const token = getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN) || ''
    return fetch('/api/chat/messages/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(data)
    })
  },

  // ---- 用户长期记忆（自服务，按 current userId 隔离）----
  /** GET /api/chat/memories/rag-mode — 用户侧全局记忆开关（非 admin，供会话开关联动/继承显示）。 */
  getChatRagMode() {
    return request.get<ApiResponse<ChatRagMode>>('/chat/memories/rag-mode')
  },
  /** GET /api/chat/memories — 当前用户全部记忆（updatedAt 倒序） */
  listMemories() {
    return request.get<ApiResponse<UserMemory[]>>('/chat/memories')
  },
  /** PUT /api/chat/memories/{id} — 行内编辑（M1）：改 key/key_zh/value/block_label，后端按需重 embed + home 重复检查 */
  updateMemory(id: number, data: MemoryEditRequest) {
    return request.put<ApiResponse<UserMemory>>(`/chat/memories/${id}`, data)
  },
  /** DELETE /api/chat/memories/{id} — 删单条 */
  deleteMemory(id: number) {
    return request.delete<ApiResponse<void>>(`/chat/memories/${id}`)
  },
  /** DELETE /api/chat/memories/batch — 批量删（ownership 过滤），返实际删除条数 */
  batchDeleteMemories(ids: number[]) {
    return request.delete<ApiResponse<number>>('/chat/memories/batch', { data: ids })
  },
  /** DELETE /api/chat/memories — 清空全部 */
  clearMemories() {
    return request.delete<ApiResponse<number>>('/chat/memories')
  },
  /** GET /api/chat/memories/conflicts — FLAGGED 冲突分组 */
  listMemoryConflicts() {
    return request.get<ApiResponse<MemoryConflict[]>>('/chat/memories/conflicts')
  },
  /** GET /api/chat/memories/incident — 取并清除记忆写入异常（弹一次即清）。data=消息 or null */
  getMemoryIncident() {
    return request.get<ApiResponse<string | null>>('/chat/memories/incident')
  },
  /** GET /api/chat/memories/status — 记忆处理状态（状态条轮询）：processingCount + conflictCount */
  getMemoryStatus() {
    return request.get<ApiResponse<MemoryStatus>>('/chat/memories/status')
  },
  /** PUT /api/chat/memories/conflicts/{id}/resolve — KEEP_NEW/KEEP_OLD/KEEP_BOTH/DISCARD/KEEP_CUSTOM。data=是否成功。
   *  M2:KEEP_CUSTOM 须传 customValue(用户手改后的值)。 */
  resolveMemoryConflict(id: number, decision: string, customValue?: string) {
    return request.put<ApiResponse<boolean>>(`/chat/memories/conflicts/${id}/resolve`,
      { decision, customValue })
  },
  /** POST /chat/memories/conflicts/batch-resolve — 批量统一解决全部 PENDING+FLAGGED，返解决条数 */
  batchResolveMemoryConflicts(decision: string) {
    return request.post<ApiResponse<number>>('/chat/memories/conflicts/batch-resolve', { decision })
  },
  /** M2:GET /chat/memories/key-meta/{key} — 读 per-key 时序标。data=null=首次待询问。 */
  getMemoryKeyMeta(key: string) {
    return request.get<ApiResponse<MemoryKeyMeta | null>>(`/chat/memories/key-meta/${encodeURIComponent(key)}`)
  },
  /** M2:PUT /chat/memories/key-meta/{key} — 手改 per-key 时序标(source=USER_OVERRIDE)。 */
  updateMemoryKeyMeta(key: string, isTemporal: boolean) {
    return request.put<ApiResponse<MemoryKeyMeta>>(`/chat/memories/key-meta/${encodeURIComponent(key)}`, { isTemporal })
  },
  /** POST /chat/memories/preview — 记忆注入预览（调试用）：传 query + 可选 scope，看实际注入 LLM 的上下文。
   *  LLM_KEY 两阶段（expand+anchor embed+key/block 双维度 rerank）最多 4 次 LLM 调用，慢网关下 >15s 全局 timeout，
   *  故单独放宽到 60s（仅此调试端点，不影响对话主链路）。 */
  previewMemoryContext(query: string, scope?: { includeGlobal?: boolean; projectIds?: number[] }) {
    return request.post<ApiResponse<MemoryContextPreview>>('/chat/memories/preview', {
      query,
      includeGlobal: scope?.includeGlobal,
      projectIds: scope?.projectIds
    }, { timeout: 60000 })
  },
  /** GET /chat/memories/{id}/scopes — 回显单条记忆 scope 归属（面板编辑用）。 */
  getMemoryScopes(id: number) {
    return request.get<ApiResponse<MemoryScopeVO>>(`/chat/memories/${id}/scopes`)
  },
  /** PUT /chat/memories/{id}/scopes — 替换该记忆全部 scope（升级 global/加项目/关 global）。 */
  updateMemoryScopes(id: number, data: { isGlobal: boolean; projectIds: number[] }) {
    return request.put<ApiResponse<MemoryScopeVO>>(`/chat/memories/${id}/scopes`, data)
  }
}
