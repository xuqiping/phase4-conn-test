// ============================================================
// 知识库模块 API
// 对应后端 /api/knowledge/** 端点
//   bases       → knowledge:read（列表/查）/ knowledge:write（建改删）
//   documents   → knowledge:read（列表/查）/ knowledge:write（上传/删）
//   permissions → knowledge:write（授权/撤销/列表）
//   retrieve    → knowledge:read（检索调试，同步 POST）
// ============================================================

import request from './request'
import type { ApiResponse } from './request'
import type { PageResult } from '@/api/admin'
import { getStorage, STORAGE_KEYS } from '@/utils/storage'

// === 类型定义 ===

/** 知识库（对应后端 KnowledgeBaseVO） */
export interface KnowledgeBase {
  id: number
  name: string
  description: string | null
  visibility: string              // PRIVATE / TEAM / PUBLIC
  embeddingModel: string | null
  rerankModel: string | null
  summaryStrategy: string | null  // PER_SECTION / BATCH / HYBRID
  status: string
  createdBy: number | null
  createdAt: string
  canManage: boolean
  canRead: boolean
}

/** 建库/改库请求（对应后端 KnowledgeBaseRequest） */
export interface KnowledgeBaseRequest {
  name: string
  description?: string
  visibility?: string
  embeddingModel?: string
  rerankModel?: string
  summaryStrategy?: string
}

/** 文档（对应后端 KnowledgeDocumentVO） */
export interface KnowledgeDocument {
  id: number
  kbId: number
  title: string
  docType: string | null
  status: string                  // PENDING/PARSING/SUMMARIZING/EMBEDDING/INDEXED/FAILED
  fileRef: string | null
  fileHash: string | null
  parseError: string | null
  createdAt: string
  updatedAt: string | null
}

/** 权限授权记录（对应后端 KnowledgePermissionVO） */
export interface KnowledgePermission {
  id: number
  targetType: string              // KB / DIRECTORY / DOCUMENT
  targetId: number
  subjectType: string             // USER / ROLE / DEPARTMENT / SERVICE_ACCOUNT
  subjectId: number
  subjectName: string | null
  canRead: boolean
  canWrite: boolean
  canManage: boolean
  grantedBy: number | null
  createdAt: string
}

/** 授权请求（对应后端 KnowledgePermissionRequest） */
export interface KnowledgePermissionRequest {
  targetType: string
  targetId: number
  subjectType: string
  subjectId: number
  canRead?: boolean
  canWrite?: boolean
  canManage?: boolean
}

/** 检索调试请求（对应后端 RagRetrieveRequest；adminHint 后端强制覆盖，前端不发） */
export interface RagRetrieveRequest {
  kbId: number
  query: string
  docTypes?: string[]
  maxL0?: number
  mode?: string                   // Phase1 仅 BALANCED
}

export interface RagCitation {
  index: number
  documentId: number
  title: string
  nodeId: number
}

export interface RagRecallHit {
  nodeId: number
  documentId: number
  title: string
  cosineDistance: number
  cosineSimilarity: number
}

export interface RagEvidence {
  nodeId: number
  documentId: number
  title: string
  content: string
  contentHash: string
  docType: string | null
  citationIndex: number
  rerankScore: number
}

export interface RagTokenBudget {
  maxContextTokens: number
  modelMaxContext: number
  answerTokenReserve: number
  effectiveContextCap: number
  promptTokens: number
}

/** 检索调试响应（对应后端 RagRetrieveVO） */
export interface RagRetrieveVO {
  traceId: string
  abstained: boolean
  abstainReason: string | null
  answer: string
  citations: RagCitation[]
  candidatesL0: RagRecallHit[]
  evidenceL2: RagEvidence[]
  tokenBudget: RagTokenBudget
  latencyMs: number
}

/** 检索审计记录（对应后端 RagRetrievalLogVO，大 JSON 字段原样透传） */
export interface RagRetrievalLog {
  id: number
  traceId: string | null
  userId: number | null
  identityType: string | null
  kbIds: string | null
  query: string | null
  mode: string | null
  l2LexicalFallback: boolean | null
  /** SUPPORTED / LOW_CONFIDENCE / NO_DENSE_HITS / NO_VISIBLE_DOCS / CITATION_CHECK_FAIL / ERROR */
  cragVerdict: string | null
  latencyMs: number | null
  createdAt: string
  candidatesL0: string | null
  evidenceL2: string | null
  tokenBudget: string | null
}

/** 检索审计分页查询（对应后端 GET 参数，ISO-8601 时间串） */
export interface RetrievalLogPageQuery {
  page?: number
  size?: number
  userId?: number
  kbId?: number
  mode?: string
  from?: string
  to?: string
}

// === API 函数 ===

export const knowledgeApi = {
  // ---- 知识库 ----
  /** GET /api/knowledge/bases — 当前用户可见的 KB 列表（knowledge:read） */
  listBases() {
    return request.get<ApiResponse<KnowledgeBase[]>>('/knowledge/bases')
  },
  getBase(id: number) {
    return request.get<ApiResponse<KnowledgeBase>>(`/knowledge/bases/${id}`)
  },
  createBase(data: KnowledgeBaseRequest) {
    return request.post<ApiResponse<KnowledgeBase>>('/knowledge/bases', data)
  },
  updateBase(id: number, data: KnowledgeBaseRequest) {
    return request.put<ApiResponse<KnowledgeBase>>(`/knowledge/bases/${id}`, data)
  },
  deleteBase(id: number) {
    return request.delete<ApiResponse<void>>(`/knowledge/bases/${id}`)
  },

  // ---- 文档 ----
  /** GET /api/knowledge/documents?kbId= — 文档列表（knowledge:read） */
  listDocuments(kbId: number) {
    return request.get<ApiResponse<KnowledgeDocument[]>>('/knowledge/documents', { params: { kbId } })
  },
  getDocument(id: number) {
    return request.get<ApiResponse<KnowledgeDocument>>(`/knowledge/documents/${id}`)
  },
  /** POST /api/knowledge/documents/upload — multipart 上传（knowledge:write） */
  uploadDocument(kbId: number, file: File) {
    const fd = new FormData()
    fd.append('kbId', String(kbId))
    fd.append('file', file)
    return request.post<ApiResponse<KnowledgeDocument>>('/knowledge/documents/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  deleteDocument(id: number) {
    return request.delete<ApiResponse<void>>(`/knowledge/documents/${id}`)
  },

  // ---- 权限 ----
  /** GET /api/knowledge/permissions?targetType=&targetId= — 某对象的授权列表（knowledge:write） */
  listPermissions(targetType: string, targetId: number) {
    return request.get<ApiResponse<KnowledgePermission[]>>('/knowledge/permissions', {
      params: { targetType, targetId }
    })
  },
  grantPermission(data: KnowledgePermissionRequest) {
    return request.post<ApiResponse<KnowledgePermission>>('/knowledge/permissions', data)
  },
  revokePermission(id: number) {
    return request.delete<ApiResponse<void>>(`/knowledge/permissions/${id}`)
  },

  // ---- 检索调试 ----
  /** POST /api/knowledge/retrieve — 检索调试，返回完整候选/证据/引用/预算（knowledge:read） */
  retrieve(data: RagRetrieveRequest) {
    return request.post<ApiResponse<RagRetrieveVO>>('/knowledge/retrieve', data)
  },

  // ---- 检索审计（knowledge:manage，管理员）----
  /** GET /api/knowledge/retrieval-logs — 审计分页（按 userId/kbId/mode/时间范围过滤） */
  pageRetrievalLogs(q: RetrievalLogPageQuery) {
    return request.get<ApiResponse<PageResult<RagRetrievalLog>>>('/knowledge/retrieval-logs', { params: q })
  },
  /** DELETE /api/knowledge/retrieval-logs/{id} — 删单条 */
  deleteRetrievalLog(id: number) {
    return request.delete<ApiResponse<void>>(`/knowledge/retrieval-logs/${id}`)
  },
  /** DELETE /api/knowledge/retrieval-logs?before=ISO-8601 — 按时间批量清理 */
  deleteRetrievalLogsBefore(before: string) {
    return request.delete<ApiResponse<number>>('/knowledge/retrieval-logs', { params: { before } })
  }
}

/** /ask SSE 流事件（对应后端 StreamEvent：CHUNK/THINKING/CITATION/DONE/ERROR）。 */
export interface AskStreamEvent {
  type: 'CHUNK' | 'THINKING' | 'CITATION' | 'DONE' | 'ERROR' | string
  content?: string
}

/**
 * POST /api/knowledge/ask — RAG 流式问答（SSE，knowledge:read）。
 * 异步生成器逐事件 yield（CHUNK 追加答案，CITATION=引用 JSON 数组，DONE 收尾）。
 */
export async function* askStream(
  query: string,
  kbIds: number[],
  signal?: AbortSignal
): AsyncGenerator<AskStreamEvent> {
  const token = getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN) || ''
  const response = await fetch('/api/knowledge/ask', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
      'Authorization': `Bearer ${token}`
    },
    signal,
    body: JSON.stringify({ query, kbIds })
  })
  if (!response.ok || !response.body) {
    throw new Error(`RAG 问答请求失败: ${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split(/\r?\n\r?\n/)
      buffer = parts.pop() || ''
      for (const part of parts) {
        const evt = parseAskSseEvent(part)
        if (evt) yield evt
      }
    }
    buffer += decoder.decode()
    const evt = parseAskSseEvent(buffer)
    if (evt) yield evt
  } finally {
    reader.releaseLock()
  }
}

function parseAskSseEvent(raw: string): AskStreamEvent | null {
  const dataLines = raw
    .split(/\r?\n/)
    .filter(line => line.startsWith('data:'))
    .map(line => line.slice(5).trimStart())
  if (dataLines.length === 0) return null
  const payload = dataLines.join('\n')
  if (!payload) return null
  try { return JSON.parse(payload) as AskStreamEvent } catch { return null }
}
