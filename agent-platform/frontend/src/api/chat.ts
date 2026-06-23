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
}

/** 用户长期记忆（对应后端 UserMemoryVO，自服务查询/管理）。 */
export interface UserMemory {
  id: number
  category: string | null         // PREFERENCE / FACT / FEEDBACK
  memoryKey: string | null
  memoryValue: string | null
  source: string | null           // INFERRED / EXPLICIT
  confidence: number | null       // 0-1，注入阈值 ≥0.5
  createdAt: string
  updatedAt: string
  conflictId: number | null
  conflictStatus: string | null   // null / FLAGGED
  conflictWith: string | null     // counterpart 摘要
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
  /** GET /api/chat/memories — 当前用户全部记忆（updatedAt 倒序） */
  listMemories() {
    return request.get<ApiResponse<UserMemory[]>>('/chat/memories')
  },
  /** DELETE /api/chat/memories/{id} — 删单条 */
  deleteMemory(id: number) {
    return request.delete<ApiResponse<void>>(`/chat/memories/${id}`)
  },
  /** DELETE /api/chat/memories — 清空全部 */
  clearMemories() {
    return request.delete<ApiResponse<number>>('/chat/memories')
  },
  /** GET /api/chat/memories/conflicts — FLAGGED 冲突分组 */
  listMemoryConflicts() {
    return request.get<ApiResponse<MemoryConflict[]>>('/chat/memories/conflicts')
  },
  /** PUT /api/chat/memories/conflicts/{id}/resolve — KEEP_NEW/KEEP_OLD/KEEP_BOTH/DISCARD */
  resolveMemoryConflict(id: number, decision: string) {
    return request.put<ApiResponse<void>>(`/chat/memories/conflicts/${id}/resolve`, { decision })
  }
}
