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
  /** 联网搜索开关（CHAT 模式，非 null 持久化到会话；ON→生成前联网检索注入）。 */
  webSearchEnabled?: boolean
  // 二期 P1（FR-006）：V33 写目标/读开关三字段（projectId/memIncludeGlobal/memReadProjectIds）已下线——
  // turns 纯个人域，召回范围走 /api/chat/memory/recall-scope 持久化偏好（MemoryRecallScopePopover）
}

// ---- 计划12 H'-4：legacy /chat/memories/* 全族客户端方法已删 ----
// 后端 MemoryController 整类移除（端点 404）；新栈记忆管理走 @/api/memory.ts（/api/chat/memory/*）。
// 下列 legacy 接口类型保留供历史引用，不再有方法调用（UserMemory/MemoryConflict/MemoryContextPreview/
// MemoryEditRequest/MemoryCandidate/MemoryPreviewCandidate/MemoryPreviewChannels/MemoryStatus/
// MemoryScopeVO/MemoryKeyMeta/ChatRagMode —— 旧 user_memories VO 形状，新栈不用）。

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

  sendMessage(sessionId: number, data: { message: string; model?: string; ragEnabled?: boolean; webSearchEnabled?: boolean }) {
    return request.post<ApiResponse<ChatResponse>>(`/chat/sessions/${sessionId}/messages`, data)
  },

  sendNewMessage(data: ChatSendRequest) {
    return request.post<ApiResponse<ChatResponse>>('/chat/messages', data)
  },

  // Streaming (SSE)
  streamMessage(sessionId: number, data: { message: string; model?: string; ragEnabled?: boolean; webSearchEnabled?: boolean }) {
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
  }
}
